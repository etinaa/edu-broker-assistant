package ai.lab.broker.agent;

import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.broker.TestBroker;
import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** get_account_summary без модели: данные своего счёта, без номера счёта в ответе. */
class BrokerToolsAccountSummaryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BrokerTools tools;

    @BeforeEach
    void setUp() {
        tools = new BrokerTools(TestBroker.demo());
    }

    private ToolResult summary(String accountId) {
        return tools.execute(accountId, new ToolCall("t1", "get_account_summary", MAPPER.createObjectNode()));
    }

    @Test
    void returnsCashAndPositionsWithNames() throws Exception {
        ToolResult result = summary("BRK-1");

        assertFalse(result.error());
        JsonNode body = MAPPER.readTree(result.content());
        assertEquals(0, new BigDecimal("10000.00").compareTo(body.get("cash").decimalValue()));
        assertEquals("USD", body.get("currency").asText());
        assertEquals(3, body.get("positions").size());
        JsonNode first = body.get("positions").get(0);
        assertEquals("DEMO", first.get("ticker").asText());
        assertEquals("Demo Software Inc.", first.get("name").asText());
        assertEquals(5, first.get("quantity").asLong());
    }

    @Test
    void differentAccountsGiveDifferentData() throws Exception {
        JsonNode body = MAPPER.readTree(summary("BRK-2").content());

        assertEquals(0, new BigDecimal("100.00").compareTo(body.get("cash").decimalValue()));
        assertEquals(1, body.get("positions").size());
        assertEquals("SMPL", body.get("positions").get(0).get("ticker").asText());
    }

    @Test
    void resultDoesNotContainAccountId() {
        assertFalse(summary("BRK-1").content().contains("BRK-"));
    }

    @Test
    void unknownAccountIsToolError() {
        assertTrue(summary("NOPE").error());
    }
}
