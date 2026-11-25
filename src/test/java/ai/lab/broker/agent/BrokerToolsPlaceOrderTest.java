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

/** place_order without a model: code parses the arguments, a refusal is an ordinary result, not is_error. */
class BrokerToolsPlaceOrderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BrokerTools tools;

    @BeforeEach
    void setUp() {
        tools = new BrokerTools(TestBroker.demo());
    }

    private ToolResult place(String accountId, String argsJson) throws Exception {
        return tools.execute(accountId, new ToolCall("t1", "place_order", MAPPER.readTree(argsJson)));
    }

    private static JsonNode json(ToolResult result) throws Exception {
        return MAPPER.readTree(result.content());
    }

    private static void assertMoney(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()), "was " + actual);
    }

    @Test
    void buyCreatesPendingOrderWithCashAfter() throws Exception {
        ToolResult result = place("BRK-1", "{\"side\":\"BUY\",\"ticker\":\"TRNC\",\"quantity\":2}");

        assertFalse(result.error());
        JsonNode body = json(result);
        assertEquals("CREATED", body.get("result").asText());
        assertEquals("PENDING_CONFIRMATION", body.get("status").asText());
        assertEquals("TRNC", body.get("ticker").asText());
        assertEquals(2, body.get("quantity").asLong());
        assertMoney("370.50", body.get("total"));
        assertMoney("9629.50", body.get("cashAfter"));
        assertTrue(body.hasNonNull("orderId"));
    }

    @Test
    void sellIncreasesCashAfter() throws Exception {
        JsonNode body = json(place("BRK-1", "{\"side\":\"SELL\",\"ticker\":\"TRNC\",\"quantity\":2}"));

        assertEquals("CREATED", body.get("result").asText());
        assertMoney("10370.50", body.get("cashAfter"));
    }

    @Test
    void lowercaseTickerIsStoredCanonical() throws Exception {
        JsonNode body = json(place("BRK-1", "{\"side\":\"BUY\",\"ticker\":\"demo\",\"quantity\":1}"));

        assertEquals("DEMO", body.get("ticker").asText());
    }

    @Test
    void fractionalQuantityIsOrdinaryRefusalNotError() throws Exception {
        ToolResult result = place("BRK-1", "{\"side\":\"BUY\",\"ticker\":\"TRNC\",\"quantity\":2.5}");

        assertFalse(result.error());
        JsonNode body = json(result);
        assertEquals("REFUSED", body.get("result").asText());
        assertEquals("INVALID_QUANTITY", body.get("reason").asText());
    }

    @Test
    void zeroAndNegativeQuantityAreRefused() throws Exception {
        for (String quantity : new String[]{"0", "-3"}) {
            ToolResult result = place("BRK-1", "{\"side\":\"BUY\",\"ticker\":\"TRNC\",\"quantity\":" + quantity + "}");
            assertFalse(result.error());
            assertEquals("INVALID_QUANTITY", json(result).get("reason").asText());
        }
    }

    @Test
    void insufficientFundsRefusalCarriesNumbers() throws Exception {
        // BRK-2: 100.00 USD, TRNC 185.25 × 1 → short by 85.25
        ToolResult result = place("BRK-2", "{\"side\":\"BUY\",\"ticker\":\"TRNC\",\"quantity\":1}");

        assertFalse(result.error());
        JsonNode body = json(result);
        assertEquals("REFUSED", body.get("result").asText());
        assertEquals("INSUFFICIENT_FUNDS", body.get("reason").asText());
        assertMoney("185.25", body.get("needed"));
        assertMoney("100.00", body.get("available"));
        assertMoney("85.25", body.get("shortfall"));
    }

    @Test
    void sellMoreThanPositionIsRefused() throws Exception {
        JsonNode body = json(place("BRK-1", "{\"side\":\"SELL\",\"ticker\":\"DEMO\",\"quantity\":6}"));

        assertEquals("INSUFFICIENT_POSITION", body.get("reason").asText());
        assertEquals(1, body.get("shortfall").asLong());
    }

    @Test
    void unknownTickerIsRefusal() throws Exception {
        ToolResult result = place("BRK-1", "{\"side\":\"BUY\",\"ticker\":\"NOPE\",\"quantity\":1}");

        assertFalse(result.error());
        assertEquals("UNKNOWN_SECURITY", json(result).get("reason").asText());
    }

    @Test
    void malformedArgumentsAreToolErrors() throws Exception {
        assertTrue(place("BRK-1", "{\"ticker\":\"TRNC\",\"quantity\":1}").error());
        assertTrue(place("BRK-1", "{\"side\":\"HOLD\",\"ticker\":\"TRNC\",\"quantity\":1}").error());
        assertTrue(place("BRK-1", "{\"side\":\"BUY\",\"quantity\":1}").error());
        assertTrue(place("BRK-1", "{\"side\":\"BUY\",\"ticker\":\"TRNC\"}").error());
    }

    @Test
    void resultNeverContainsAccountId() throws Exception {
        String created = place("BRK-1", "{\"side\":\"BUY\",\"ticker\":\"TRNC\",\"quantity\":1}").content();
        String refused = place("BRK-2", "{\"side\":\"BUY\",\"ticker\":\"TRNC\",\"quantity\":1}").content();

        assertFalse(created.contains("BRK-"));
        assertFalse(refused.contains("BRK-"));
    }

    @Test
    void unknownAccountIsRefusal() throws Exception {
        ToolResult result = place("BRK-9", "{\"side\":\"BUY\",\"ticker\":\"TRNC\",\"quantity\":1}");

        assertEquals("REFUSED", json(result).get("result").asText());
    }
}
