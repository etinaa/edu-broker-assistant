package ai.lab.broker.web;

import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.domain.OrderStatus;
import ai.lab.broker.llm.ScriptedConversationFactory;
import ai.lab.broker.llm.ToolConversationFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 8: /chat end to end on the full context — real tools, broker and sessions, a scripted model instead of
 * a provider. Fast feedback before runs on a real model. Demo data: BRK-1 cash 10000.00, TRNC=20;
 * BRK-2 cash 100.00, SMPL=10. The context is shared; {@link DemoState} resets accounts, orders, sessions and
 * traces before each test.
 */
@SpringBootTest(properties = {"llm.provider=ollama", "agent.max-steps=4"})
@AutoConfigureMockMvc
class ChatIntegrationTest {

    private static final String HEADER = "X-Account-Id";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestConfiguration
    static class ScriptedModel {
        @Bean
        @Primary
        ScriptedConversationFactory scriptedConversationFactory() {
            return new ScriptedConversationFactory();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ScriptedConversationFactory model;

    @Autowired
    private ToolConversationFactory injectedFactory;

    @Autowired
    private BrokerService broker;

    @Autowired
    private ApplicationContext context;

    @BeforeEach
    void setUp() {
        DemoState.reset(context);
        model.reset();
    }

    private ResultActions chat(String accountId, String sessionId, String message) throws Exception {
        String body = JSON.createObjectNode().put("sessionId", sessionId).put("message", message).toString();
        return mvc.perform(post("/chat").header(HEADER, accountId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static JsonNode body(ResultActions result) throws IOException {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    @Test
    void scriptedModelReplacesTheProvider() {
        assertSame(model, injectedFactory);
    }

    @Test
    void buyFlowCreatesPendingOrderThatTheUserConfirms() throws Exception {
        model.callsTool("find_security", "{\"query\":\"Training Corp common\"}")
                .callsTool("place_order", "{\"side\":\"BUY\",\"ticker\":\"TRNC\",\"quantity\":2}")
                .answers("Order for 2 TRNC is ready, confirm it in the UI");

        JsonNode response = body(chat("BRK-1", "s1", "buy 2 Training Corp common")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Order for 2 TRNC is ready, confirm it in the UI"))
                .andExpect(jsonPath("$.pendingOrder.ticker").value("TRNC"))
                .andExpect(jsonPath("$.pendingOrder.quantity").value(2))
                .andExpect(jsonPath("$.pendingOrder.total").value(370.50))
                .andExpect(jsonPath("$.pendingOrder.cashAfter").value(9629.50))
                .andExpect(jsonPath("$.pendingOrder.status").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.limitReached").value(false)));

        // The model only prepared the order: money has not moved until the user confirms.
        assertEquals(0, new BigDecimal("10000.00").compareTo(broker.accountById("BRK-1").orElseThrow().getCash()));

        String orderId = response.path("pendingOrder").path("orderId").asText();
        mvc.perform(post("/orders/" + orderId + "/confirm").header(HEADER, "BRK-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));
        assertEquals(0, new BigDecimal("9629.50").compareTo(broker.accountById("BRK-1").orElseThrow().getCash()));
    }

    @Test
    void ambiguousNameReturnsCandidates() throws Exception {
        model.callsTool("find_security", "{\"query\":\"Training\"}")
                .answers("Which one: TRNC, TRNP or TREN?");

        chat("BRK-1", "s1", "buy Training")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates.length()").value(3))
                .andExpect(jsonPath("$.candidates[0].ticker").value("TRNC"))
                .andExpect(jsonPath("$.pendingOrder").doesNotExist());
    }

    @Test
    void refusalIsAnOrdinaryAnswerWithoutPendingOrder() throws Exception {
        model.callsTool("place_order", "{\"side\":\"BUY\",\"ticker\":\"TRNC\",\"quantity\":1}")
                .answers("Not enough cash: short by 85.25 USD");

        chat("BRK-2", "s1", "buy 1 TRNC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingOrder").doesNotExist());

        assertTrue(model.lastConversation().toolResults().getFirst().content().contains("\"shortfall\":85.25"));
    }

    @Test
    void modelCannotSwitchAccountAndNeverSeesAccountId() throws Exception {
        // «покажи BRK-2»: the schema has no account parameter, an extra argument is ignored.
        model.callsTool("get_account_summary", "{\"accountId\":\"BRK-2\"}")
                .answers("You have 10000.00 USD");

        chat("BRK-1", "s1", "show my summary").andExpect(status().isOk());

        var conversation = model.lastConversation();
        assertTrue(conversation.toolResults().getFirst().content().contains("10000"));
        assertFalse(conversation.everythingSeen().contains("BRK-"));
    }

    @Test
    void sessionKeepsHistoryAndResetStartsOver() throws Exception {
        model.answers("first").answers("second").answers("after reset");

        chat("BRK-1", "s1", "one").andExpect(status().isOk());
        chat("BRK-1", "s1", "two").andExpect(status().isOk());
        assertEquals(1, model.conversations().size());
        assertEquals(List.of("one", "two"), model.lastConversation().userMessages());

        mvc.perform(delete("/chat/s1").header(HEADER, "BRK-1")).andExpect(status().isNoContent());
        chat("BRK-1", "s1", "three").andExpect(status().isOk());

        assertEquals(2, model.conversations().size());
        assertEquals(List.of("three"), model.lastConversation().userMessages());
    }

    @Test
    void sameSessionIdOnAnotherAccountIsAnotherConversation() throws Exception {
        model.answers("for BRK-1").answers("for BRK-2");

        chat("BRK-1", "shared", "hi").andExpect(status().isOk());
        chat("BRK-2", "shared", "hi").andExpect(status().isOk());

        assertEquals(2, model.conversations().size());
    }

    @Test
    void stepLimitGivesHonestAnswer() throws Exception {
        for (int i = 0; i < 4; i++) {
            model.callsTool("get_quote", "{\"ticker\":\"TRNC\"}");
        }

        chat("BRK-1", "s1", "keep checking the price of TRNC")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limitReached").value(true))
                .andExpect(jsonPath("$.answer").value(containsString("step limit")));
    }

    @Test
    void traceIsAvailableForTheAnswer() throws Exception {
        model.callsTool("get_quote", "{\"ticker\":\"SMPL\"}").answers("SMPL costs 12.30 USD");

        String traceId = body(chat("BRK-1", "s1", "price of SMPL")).path("traceId").asText();

        mvc.perform(get("/traces/" + traceId).header(HEADER, "BRK-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("price of SMPL"))
                .andExpect(jsonPath("$.model").value("scripted/test"))
                .andExpect(jsonPath("$.steps.length()").value(2))
                .andExpect(jsonPath("$.steps[0].calls[0].tool").value("get_quote"));
    }

    @Test
    void traceOfAnotherAccountIs404() throws Exception {
        model.callsTool("get_account_summary", "{}").answers("You have 10000.00 USD");

        String traceId = body(chat("BRK-1", "s1", "what do I have")).path("traceId").asText();

        mvc.perform(get("/traces/" + traceId).header(HEADER, "BRK-2"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("10000"))));
    }

    @Test
    void traceWithoutAccountHeaderIs400() throws Exception {
        model.answers("hi");

        String traceId = body(chat("BRK-1", "s1", "hi")).path("traceId").asText();

        mvc.perform(get("/traces/" + traceId)).andExpect(status().isBadRequest());
    }

    @Test
    void failedMessageIsRolledBackAndItsOrderCancelled() throws Exception {
        model.answers("hello")
                .callsTool("place_order", "{\"side\":\"BUY\",\"ticker\":\"TRNC\",\"quantity\":2}")
                .fails(new IllegalStateException("Claude 529: overloaded"))
                .answers("retry answer");

        chat("BRK-1", "s1", "hi").andExpect(status().isOk());
        chat("BRK-1", "s1", "buy 2 TRNC").andExpect(status().isBadGateway());

        var conversation = model.lastConversation();
        assertEquals(List.of("hi"), conversation.userMessages());
        assertTrue(conversation.toolResults().isEmpty());
        assertEquals(OrderStatus.CANCELLED, broker.orderById("ORD-1").orElseThrow().getStatus());

        chat("BRK-1", "s1", "buy 2 TRNC").andExpect(status().isOk());
        assertEquals(List.of("hi", "buy 2 TRNC"), conversation.userMessages());
    }

    @Test
    void providerFailureIs502WithoutProviderDetails() throws Exception {
        model.fails(new IllegalStateException("Claude 529: {\"error\":\"overloaded\",\"request_id\":\"secret\"}"));

        chat("BRK-1", "s1", "hi")
                .andExpect(status().isBadGateway())
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void tickerTheModelCorrectedIsRejectedAndNoOrderIsCreated() throws Exception {
        // The user wrote a name, the model jumped to a ticker it never saw; after find_security the same ticker is fine.
        model.callsTool("place_order", "{\"side\":\"BUY\",\"ticker\":\"TRNC\",\"quantity\":2}")
                .callsTool("find_security", "{\"query\":\"Training Corp common\"}")
                .callsTool("place_order", "{\"side\":\"BUY\",\"ticker\":\"TRNC\",\"quantity\":2}")
                .answers("Order for 2 TRNC is ready");

        chat("BRK-1", "s1", "buy 2 Training Corp common")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingOrder.orderId").value("ORD-1"));

        var rejected = model.lastConversation().toolResults().getFirst();
        assertTrue(rejected.error());
        assertTrue(rejected.content().contains("find_security"));
        assertTrue(broker.orderById("ORD-2").isEmpty());
    }

    @Test
    void tickerFromThePreviousMessageOfTheSessionIsGrounded() throws Exception {
        // Question 8: "buy 100 Sample" -> two candidates -> "Logistics" -> order SMPL from the earlier results.
        model.callsTool("find_security", "{\"query\":\"Sample\"}")
                .answers("Which one: SMPB or SMPL?")
                .callsTool("place_order", "{\"side\":\"BUY\",\"ticker\":\"SMPL\",\"quantity\":100}")
                .answers("Order for 100 SMPL is ready");

        chat("BRK-1", "s1", "buy 100 shares of Sample").andExpect(status().isOk());
        chat("BRK-1", "s1", "Logistics")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingOrder.ticker").value("SMPL"))
                .andExpect(jsonPath("$.pendingOrder.total").value(1230.00));
    }

    @Test
    void tickerOnlyTheModelItselfSaidIsNotGrounded() throws Exception {
        model.answers("Did you mean TSLA?")
                .callsTool("place_order", "{\"side\":\"BUY\",\"ticker\":\"TSLA\",\"quantity\":1}")
                .answers("I could not place it");

        chat("BRK-1", "s1", "buy Tesla").andExpect(status().isOk());
        chat("BRK-1", "s1", "yes").andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingOrder").doesNotExist());

        assertTrue(model.lastConversation().toolResults().getFirst().error());
    }

    @Test
    void unknownAccountIs404AndModelIsNotCalled() throws Exception {
        chat("NOPE", "s1", "hi").andExpect(status().isNotFound());

        assertTrue(model.conversations().isEmpty());
    }

    @Test
    void blankMessageIs400() throws Exception {
        chat("BRK-1", "s1", " ").andExpect(status().isBadRequest());

        assertTrue(model.conversations().isEmpty());
    }
}
