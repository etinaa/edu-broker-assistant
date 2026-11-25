package ai.lab.broker.agent;

import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.broker.TestBroker;
import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** get_quote without a model: the price by ticker, or an error for the model. */
class BrokerToolsGetQuoteTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BrokerTools tools;
    private BrokerService broker;

    @BeforeEach
    void setUp() {
        broker = TestBroker.demo();
        tools = new BrokerTools(broker);
    }

    private ToolResult quote(String ticker) {
        var args = MAPPER.createObjectNode();
        if (ticker != null) {
            args.put("ticker", ticker);
        }
        return tools.execute("BRK-1", new ToolCall("t1", "get_quote", args));
    }

    @Test
    void returnsCanonicalTickerAndPrice() throws Exception {
        ToolResult result = quote("smpl");

        assertFalse(result.error());
        JsonNode body = MAPPER.readTree(result.content());
        assertEquals("SMPL", body.get("ticker").asText());
        assertEquals(0, broker.currentPriceByTicker("SMPL").orElseThrow().compareTo(body.get("price").decimalValue()));
        assertEquals("USD", body.get("currency").asText());
    }

    @Test
    void unknownTickerIsToolError() {
        assertTrue(quote("NOPE").error());
    }

    @Test
    void missingOrBlankTickerIsToolError() {
        assertTrue(quote(null).error());
        assertTrue(quote("  ").error());
    }
}
