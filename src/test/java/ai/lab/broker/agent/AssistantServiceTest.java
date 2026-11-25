package ai.lab.broker.agent;

import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.broker.TestBroker;
import ai.lab.broker.config.AgentProperties;
import ai.lab.broker.domain.OrderSide;
import ai.lab.broker.domain.OrderStatus;
import ai.lab.broker.domain.Security;
import ai.lab.broker.llm.LlmResponse;
import ai.lab.broker.llm.ToolConversation;
import ai.lab.broker.web.dto.ChatResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** AssistantService on a scripted conversation: real tools and broker, no model. */
class AssistantServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Deque<ToolConversation.Turn> script = new ArrayDeque<>();
    private final List<String> userMessages = new ArrayList<>();
    private final List<ToolConversation.ToolResult> toolResults = new ArrayList<>();
    private int created;
    private RuntimeException failure;
    private RuntimeException toolResultsFailure;
    /** When set, the model "thinks" inside send() until the test releases it: a long model call. */
    private volatile CountDownLatch sendEntered;
    private volatile CountDownLatch sendRelease;

    private BrokerService broker;
    private TraceStore traces;
    private AssistantService service;

    @BeforeEach
    void setUp() {
        broker = TestBroker.demo();
        traces = new TraceStore(new AgentProperties(3, 0.0, 40, 100, 100, Duration.ofSeconds(2)));
        service = new AssistantService((system, tools) -> {
            created++;
            return new ScriptedConversation();
        }, new BrokerTools(broker), broker, new SessionStore(new AgentProperties(3, 0.0, 40, 100, 100, Duration.ofSeconds(2))), traces,
                new AgentProperties(3, 0.0, 40, 100, 100, Duration.ofSeconds(2)));
    }

    private static LlmResponse response(String text) {
        return new LlmResponse(text, 10, 5, 1, "end_turn", "0.001");
    }

    private void callsTool(String name, String argumentsJson) throws Exception {
        script.add(new ToolConversation.Turn(
                List.of(new ToolConversation.ToolCall("c" + script.size(), name, MAPPER.readTree(argumentsJson))),
                response("")));
    }

    private void answers(String text) {
        script.add(new ToolConversation.Turn(List.of(), response(text)));
    }

    @Test
    void plainAnswerIsReturnedAndTraced() {
        answers("Hello");

        ChatResponse response = service.chat("BRK-1", "s1", "hi");

        assertEquals("Hello", response.answer());
        assertNull(response.pendingOrder());
        assertTrue(response.candidates().isEmpty());
        assertFalse(response.limitReached());
        assertEquals(List.of("hi"), userMessages);

        Trace trace = traces.find(response.traceId()).orElseThrow();
        assertEquals("hi", trace.message());
        assertEquals(1, trace.steps().size());
        assertEquals("Hello", trace.answer());
    }

    @Test
    void createdOrderBecomesPendingOrder() throws Exception {
        callsTool("place_order", "{\"side\":\"BUY\",\"ticker\":\"TRNC\",\"quantity\":2}");
        answers("Order created, confirm it in the UI");

        ChatResponse response = service.chat("BRK-1", "s1", "buy 2 TRNC");

        ChatResponse.PendingOrder order = response.pendingOrder();
        assertNotNull(order);
        assertEquals(OrderSide.BUY, order.side());
        assertEquals("TRNC", order.ticker());
        assertEquals(2, order.quantity());
        assertEquals(OrderStatus.PENDING_CONFIRMATION, order.status());
        assertEquals(new BigDecimal("370.50"), order.total());
        assertEquals(new BigDecimal("9629.50"), order.cashAfter());
        assertTrue(broker.orderById(order.orderId()).isPresent());
    }

    @Test
    void refusedOrderIsNotPending() throws Exception {
        callsTool("place_order", "{\"side\":\"BUY\",\"ticker\":\"TRNC\",\"quantity\":1}");
        answers("Not enough cash");

        assertNull(service.chat("BRK-2", "s1", "buy TRNC").pendingOrder());
    }

    @Test
    void candidatesComeFromFindSecurityResult() throws Exception {
        callsTool("find_security", "{\"query\":\"Training\"}");
        answers("Which one?");

        ChatResponse response = service.chat("BRK-1", "s1", "buy Training");

        assertEquals(List.of("TRNC", "TRNP", "TREN"), response.candidates().stream().map(Security::ticker).toList());
    }

    @Test
    void laterExactMatchClearsCandidates() throws Exception {
        callsTool("find_security", "{\"query\":\"Training\"}");
        callsTool("find_security", "{\"query\":\"TRNC\"}");
        answers("Found TRNC");

        assertTrue(service.chat("BRK-1", "s1", "Training common").candidates().isEmpty());
    }

    @Test
    void toolsRunOnTheRequestAccount() throws Exception {
        callsTool("get_account_summary", "{}");
        answers("You have 100 USD");

        service.chat("BRK-2", "s1", "what do I have");

        JsonAssert.cash(toolResults.getFirst().content(), "100.00");
    }

    @Test
    void limitReachedGivesHonestAnswer() throws Exception {
        for (int i = 0; i < 3; i++) {
            callsTool("get_quote", "{\"ticker\":\"TRNC\"}");
        }

        ChatResponse response = service.chat("BRK-1", "s1", "loop");

        assertTrue(response.limitReached());
        assertEquals(AssistantService.LIMIT_REACHED_ANSWER, response.answer());
        assertTrue(traces.find(response.traceId()).orElseThrow().limitReached());
    }

    @Test
    void sessionIsReusedPerAccountAndSession() {
        answers("1");
        answers("2");
        answers("3");

        service.chat("BRK-1", "s1", "a");
        service.chat("BRK-1", "s1", "b");
        service.chat("BRK-2", "s1", "c");

        assertEquals(2, created);
    }

    @Test
    void providerFailureIsAssistantUnavailable() {
        failure = new IllegalStateException("Claude 529: {\"secret\":\"body\"}");

        AssistantUnavailableException e = assertThrows(AssistantUnavailableException.class,
                () -> service.chat("BRK-1", "s1", "hi"));
        assertFalse(e.getMessage().contains("secret"));
    }

    @Test
    void bugInOurCodeIsNotDisguisedAsProviderFailureAndIsRolledBack() throws Exception {
        callsTool("place_order", "{\"side\":\"BUY\",\"ticker\":\"TRNC\",\"quantity\":2}");
        toolResultsFailure = new IllegalArgumentException("bug");

        RuntimeException e = assertThrows(RuntimeException.class, () -> service.chat("BRK-1", "s1", "buy 2 TRNC"));

        assertSame(toolResultsFailure, e);
        assertEquals(OrderStatus.CANCELLED, broker.orderById("ORD-1").orElseThrow().getStatus());
    }

    @Test
    void failureAfterTheLoopRollsBackHistoryAndCancelsTheOrder() throws Exception {
        AgentProperties agent = new AgentProperties(3, 0.0, 40, 100, 100, Duration.ofSeconds(2));
        RuntimeException traceFailure = new IllegalArgumentException("trace store bug");
        TraceStore failingTraces = new TraceStore(agent) {
            @Override
            public synchronized void save(Trace trace) {
                throw traceFailure;
            }
        };
        ScriptedConversation conversation = new ScriptedConversation();
        AssistantService failing = new AssistantService((system, tools) -> conversation, new BrokerTools(broker),
                broker, new SessionStore(agent), failingTraces, agent);
        callsTool("place_order", "{\"side\":\"BUY\",\"ticker\":\"TRNC\",\"quantity\":2}");
        answers("Order created");

        assertSame(traceFailure, assertThrows(RuntimeException.class,
                () -> failing.chat("BRK-1", "s1", "buy 2 TRNC")));

        assertEquals(0, conversation.size());
        assertEquals(OrderStatus.CANCELLED, broker.orderById("ORD-1").orElseThrow().getStatus());
    }

    @Test
    void busySessionRefusesAnotherMessageAndResetWhileOtherSessionsWork() throws Exception {
        AgentProperties agent = new AgentProperties(3, 0.0, 40, 100, 100, Duration.ofMillis(50));
        AssistantService busy = new AssistantService((system, tools) -> new ScriptedConversation(),
                new BrokerTools(broker), broker, new SessionStore(agent), traces, agent);
        answers("first");
        answers("other session");
        sendEntered = new CountDownLatch(1);
        sendRelease = new CountDownLatch(1);
        CountDownLatch release = sendRelease;
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ChatResponse> first = pool.submit(() -> busy.chat("BRK-1", "s1", "one"));
            assertTrue(sendEntered.await(5, TimeUnit.SECONDS));
            sendRelease = null; // only the first message is slow

            assertThrows(SessionBusyException.class, () -> busy.chat("BRK-1", "s1", "two"));
            assertThrows(SessionBusyException.class, () -> busy.resetSession("BRK-1", "s1"));
            assertEquals("other session", busy.chat("BRK-1", "s2", "hi").answer());

            release.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS).answer());
            assertEquals(List.of("one", "hi"), userMessages); // "two" never reached the history
            assertDoesNotThrow(() -> busy.resetSession("BRK-1", "s1"));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void negativeSessionBusyTimeoutIsRejectedAtStartup() {
        AgentProperties negative = new AgentProperties(3, 0.0, 40, 100, 100, Duration.ofMillis(-1));

        assertThrows(IllegalArgumentException.class, () -> new AssistantService((system, tools) -> null,
                new BrokerTools(broker), broker, new SessionStore(negative), new TraceStore(negative), negative));
    }

    @Test
    void nonPositiveMaxStepsIsRejectedAtStartup() {
        AgentProperties zeroSteps = new AgentProperties(0, 0.0, 40, 100, 100, Duration.ofSeconds(2));

        assertThrows(IllegalArgumentException.class, () -> new AssistantService((system, tools) -> null,
                new BrokerTools(broker), broker, new SessionStore(zeroSteps), new TraceStore(zeroSteps), zeroSteps));
    }

    private static final class JsonAssert {
        static void cash(String json, String expected) throws Exception {
            assertEquals(0, new BigDecimal(expected).compareTo(MAPPER.readTree(json).get("cash").decimalValue()));
        }
    }

    private final class ScriptedConversation implements ToolConversation {
        private int size;

        @Override
        public void addUserMessage(String text) {
            userMessages.add(text);
            size++;
        }

        @Override
        public Turn send() throws InterruptedException {
            if (failure != null) {
                throw failure;
            }
            size++;
            Turn turn = script.removeFirst();
            CountDownLatch release = sendRelease;
            if (release != null) {
                sendEntered.countDown();
                release.await();
            }
            return turn;
        }

        @Override
        public void addToolResults(List<ToolResult> results) {
            if (toolResultsFailure != null) {
                throw toolResultsFailure;
            }
            toolResults.addAll(results);
            size++;
        }

        @Override
        public Seen seen() {
            return new Seen(List.copyOf(userMessages),
                    toolResults.stream().filter(result -> !result.error()).map(ToolResult::content).toList());
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public void truncate(int size) {
            this.size = size;
        }

        @Override
        public boolean dropOldestExchange() {
            return false;
        }

        @Override
        public String name() {
            return "scripted";
        }
    }
}
