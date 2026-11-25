package ai.lab.broker.agent;

import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.config.AgentProperties;
import ai.lab.broker.domain.Security;
import ai.lab.broker.llm.ToolConversation;
import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;
import ai.lab.broker.llm.ToolConversationFactory;
import ai.lab.broker.web.dto.ChatResponse;
import ai.lab.broker.web.dto.ChatResponse.PendingOrder;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Handles one user message: session → agent loop → structured response → trace.
 */
@Slf4j
@Service
public class AssistantService {

    static final String LIMIT_REACHED_ANSWER =
            "I could not finish this request within the step limit. Please rephrase it or split it into smaller steps.";

    /** Exact BigDecimal for floats, so cashAfter keeps its scale (9629.50, not 9629.5). */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

    private final ToolConversationFactory conversations;
    private final BrokerTools tools;
    private final BrokerService broker;
    private final SessionStore sessions;
    private final TraceStore traces;
    private final AgentProperties agent;

    public AssistantService(ToolConversationFactory conversations, BrokerTools tools, BrokerService broker,
                            SessionStore sessions, TraceStore traces, AgentProperties agent) {
        this.conversations = conversations;
        this.tools = tools;
        this.broker = broker;
        this.sessions = sessions;
        this.traces = traces;
        this.agent = agent;
        if (agent.maxSteps() <= 0) {
            throw new IllegalArgumentException("agent.max-steps must be positive: " + agent.maxSteps());
        }
        if (agent.sessionBusyTimeout() == null || agent.sessionBusyTimeout().isNegative()) {
            throw new IllegalArgumentException(
                    "agent.session-busy-timeout must be zero or positive: " + agent.sessionBusyTimeout());
        }
    }

    /**
     * Task 4. accountId comes from the request header and travels only through code: the model never sees it.
     * <ol>
     *   <li>The session is locked for the whole request, so two parallel messages of one session cannot interleave
     *       in its history. The lock is awaited at most {@code agent.session-busy-timeout}; then the message is
     *       refused with {@link SessionBusyException} instead of holding a server thread while the model answers
     *       the other one.</li>
     *   <li>Before the new message, the history is trimmed to the session limit ({@link SessionStore#fitHistory}).</li>
     *   <li>The executor is created per request and closes over accountId. It is wrapped in a
     *       {@link GroundedExecutor}: a ticker must appear in the user's messages or the successful tool results of
     *       this session (as far as the history still holds them), otherwise the call is rejected without running.
     *       The whole session counts, not just this message: "buy 100 Sample" → candidates → "Logistics" places
     *       SMPL, which came from the previous message's find_security. An {@link AmbiguityGuard} then refuses
     *       other tools after find_security returned CANDIDATES, so the model asks instead of picking one.</li>
     *   <li>When the step limit is reached, the answer is an honest "could not finish", not a fragment of the
     *       model's text.</li>
     *   <li>pendingOrder and candidates are built from this message's tool results, not from the model's text.</li>
     * </ol>
     * Provider failures (network, 429, 5xx) become {@link AssistantUnavailableException} without provider details;
     * any other exception is a bug in our code and propagates as is, so it is not disguised as a provider outage.
     * Either way, and also when building the response or saving the trace fails, the failed message leaves no trace
     * in the session: the history is rolled back to where it was before it, and orders created during it are
     * cancelled. The user never saw them, and a retry must not leave a duplicate.
     */
    public ChatResponse chat(String accountId, String sessionId, String message) {
        SessionStore.Session session = lockCurrentSession(accountId, sessionId);
        try {
            ToolConversation conversation = session.conversation();
            sessions.fitHistory(conversation);
            int sizeBefore = conversation.size();
            List<ToolResult> executed = new ArrayList<>();
            conversation.addUserMessage(message);
            try {
                ToolLoop.Result result = runLoop(conversation, new GroundedExecutor(conversation.seen(),
                        new AmbiguityGuard(call -> {
                            ToolResult toolResult = tools.execute(accountId, call);
                            executed.add(toolResult);
                            return toolResult;
                        })));
                // Built inside the try: until the response exists, the user has seen nothing of this message.
                return respond(accountId, sessionId, message, conversation.name(), result);
            } catch (RuntimeException e) {
                conversation.truncate(sizeBefore);
                cancelCreatedOrders(accountId, executed);
                throw e;
            }
        } finally {
            session.lock().unlock();
        }
    }

    /**
     * The session's lock, taken. If the session was reset or evicted while we waited, its conversation is forgotten:
     * the message goes to the session that replaced it, never to a history nobody will see again.
     */
    private SessionStore.Session lockCurrentSession(String accountId, String sessionId) {
        while (true) {
            SessionStore.Session session = sessions.getOrCreate(accountId, sessionId,
                    () -> conversations.create(SystemPrompt.TEXT, tools.definitions()));
            lock(session);
            if (sessions.isCurrent(accountId, sessionId, session)) {
                return session;
            }
            session.lock().unlock();
        }
    }

    private void lock(SessionStore.Session session) {
        try {
            if (!session.lock().tryLock(agent.sessionBusyTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new SessionBusyException();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionBusyException();
        }
    }

    /** The structured response and its trace; the trace is saved last, so a failure here leaves no trace behind. */
    private ChatResponse respond(String accountId, String sessionId, String message, String model,
                                 ToolLoop.Result result) {
        List<ToolResult> results = result.steps().stream()
                .flatMap(step -> step.results().stream())
                .toList();
        String answer = result.limitReached() ? LIMIT_REACHED_ANSWER : result.answer();
        PendingOrder pendingOrder = pendingOrder(results);
        List<Security> candidates = candidates(results);

        Trace trace = new Trace(traces.nextId(), accountId, sessionId, message, model,
                result.steps().stream().map(Trace.Step::from).toList(),
                answer, result.limitReached(), Instant.now());
        traces.save(trace);

        return new ChatResponse(answer, pendingOrder, candidates, result.limitReached(), trace.id());
    }

    /**
     * New session with an empty history. Waits, like a message, for the session's current message to be answered:
     * a reset in the middle of an answer would leave that answer in a history nobody sees.
     */
    public void resetSession(String accountId, String sessionId) {
        sessions.find(accountId, sessionId).ifPresent(session -> {
            lock(session);
            try {
                sessions.remove(accountId, sessionId, session);
            } finally {
                session.lock().unlock();
            }
        });
    }

    private ToolLoop.Result runLoop(ToolConversation conversation, Function<ToolCall, ToolResult> executor) {
        try {
            return ToolLoop.run(conversation, executor, agent.maxSteps());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssistantUnavailableException("The request to the model was interrupted", e);
        } catch (ModelCallException e) {
            log.error("Model provider failed for {}", conversation.name(), e.getCause());
            throw new AssistantUnavailableException("The model is unavailable right now, please try again later", e);
        }
    }

    /** Orders the failed message created are rejected, so nothing the user never saw stays confirmable. */
    private void cancelCreatedOrders(String accountId, List<ToolResult> executed) {
        createdOrders(executed).forEach(body -> {
            String orderId = body.path("orderId").asText();
            log.warn("Cancelling order {} created by a failed chat request", orderId);
            broker.cancelUnseenOrder(accountId, orderId, "Cancelled: the chat request that created it failed");
        });
    }

    /** Results of place_order that created an order, in call order. */
    private static Stream<JsonNode> createdOrders(List<ToolResult> results) {
        return results.stream()
                .filter(result -> isSuccessOf(result, "place_order"))
                .map(AssistantService::parse)
                .filter(body -> "CREATED".equals(body.path("result").asText()));
    }

    /** The last order created by place_order in this message, or null; the order is read from the order book. */
    private PendingOrder pendingOrder(List<ToolResult> results) {
        return createdOrders(results.reversed())
                .findFirst()
                .flatMap(body -> broker.orderById(body.path("orderId").asText())
                        .map(order -> PendingOrder.from(order, body.path("cashAfter").decimalValue())))
                .orElse(null);
    }

    /** Securities to choose from, if the last find_security of this message returned CANDIDATES. */
    private List<Security> candidates(List<ToolResult> results) {
        return results.reversed().stream()
                .filter(result -> isSuccessOf(result, "find_security"))
                .map(AssistantService::parse)
                .findFirst()
                .filter(body -> "CANDIDATES".equals(body.path("match").asText()))
                .map(body -> StreamSupport.stream(body.path("securities").spliterator(), false)
                        .map(security -> broker.securityByTicker(security.path("ticker").asText()))
                        .flatMap(Optional::stream)
                        .toList())
                .orElse(List.of());
    }

    private static boolean isSuccessOf(ToolResult result, String tool) {
        return !result.error() && tool.equals(result.call().name());
    }

    /** Tool results are JSON written by {@link BrokerTools}; unparsable content counts as an empty object. */
    private static JsonNode parse(ToolResult result) {
        try {
            return JSON.readTree(result.content());
        } catch (Exception e) {
            return JSON.createObjectNode();
        }
    }
}
