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
    }

    /**
     * Task 4. accountId comes from the request header and travels only through code: the model never sees it.
     * <ol>
     *   <li>The session's conversation is locked for the whole request, so two parallel messages of one session
     *       cannot interleave in its history.</li>
     *   <li>Before the new message, the history is trimmed to the session limit ({@link SessionStore#fitHistory}).</li>
     *   <li>The executor is created per request and closes over accountId.</li>
     *   <li>When the step limit is reached, the answer is an honest "could not finish", not a fragment of the
     *       model's text.</li>
     *   <li>pendingOrder and candidates are built from this message's tool results, not from the model's text.</li>
     * </ol>
     * Provider failures (network, 429, 5xx) become {@link AssistantUnavailableException} without provider details.
     * The failed message leaves no trace in the session: the history is rolled back to where it was before it, and
     * orders created during it are cancelled. The user never saw them, and a retry must not leave a duplicate.
     */
    public ChatResponse chat(String accountId, String sessionId, String message) {
        ToolConversation conversation = sessions.getOrCreate(accountId, sessionId,
                () -> conversations.create(SystemPrompt.TEXT, tools.definitions()));

        ToolLoop.Result result;
        synchronized (conversation) {
            sessions.fitHistory(conversation);
            int sizeBefore = conversation.size();
            List<ToolResult> executed = new ArrayList<>();
            conversation.addUserMessage(message);
            try {
                result = runLoop(conversation, call -> {
                    ToolResult toolResult = tools.execute(accountId, call);
                    executed.add(toolResult);
                    return toolResult;
                });
            } catch (AssistantUnavailableException e) {
                conversation.truncate(sizeBefore);
                cancelCreatedOrders(accountId, executed);
                throw e;
            }
        }

        List<ToolResult> results = result.steps().stream()
                .flatMap(step -> step.results().stream())
                .toList();
        String answer = result.limitReached() ? LIMIT_REACHED_ANSWER : result.answer();

        Trace trace = new Trace(traces.nextId(), accountId, sessionId, message, conversation.name(),
                result.steps().stream().map(Trace.Step::from).toList(),
                answer, result.limitReached(), Instant.now());
        traces.save(trace);

        return new ChatResponse(answer, pendingOrder(results), candidates(results), result.limitReached(), trace.id());
    }

    /** New session with an empty history. */
    public void resetSession(String accountId, String sessionId) {
        sessions.remove(accountId, sessionId);
    }

    private ToolLoop.Result runLoop(ToolConversation conversation, Function<ToolCall, ToolResult> executor) {
        try {
            return ToolLoop.run(conversation, executor, agent.maxSteps());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssistantUnavailableException("The request to the model was interrupted", e);
        } catch (Exception e) {
            log.error("Model provider failed for {}", conversation.name(), e);
            throw new AssistantUnavailableException("The model is unavailable right now, please try again later", e);
        }
    }

    /** Orders the failed message created are rejected, so nothing the user never saw stays confirmable. */
    private void cancelCreatedOrders(String accountId, List<ToolResult> executed) {
        createdOrders(executed).forEach(body -> {
            String orderId = body.path("orderId").asText();
            log.warn("Cancelling order {} created by a failed chat request", orderId);
            broker.rejectOrder(accountId, orderId);
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
