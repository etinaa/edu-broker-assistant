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

/** find_security without a model: match and the list of securities in JSON, with no account number. */
class BrokerToolsFindSecurityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BrokerTools tools;

    @BeforeEach
    void setUp() {
        tools = new BrokerTools(TestBroker.demo());
    }

    private ToolResult find(String query) {
        var args = MAPPER.createObjectNode();
        if (query != null) {
            args.put("query", query);
        }
        return tools.execute("BRK-1", new ToolCall("t1", "find_security", args));
    }

    @Test
    void exactTickerReturnsOneSecurity() throws Exception {
        ToolResult result = find("smpl");

        assertFalse(result.error());
        JsonNode body = MAPPER.readTree(result.content());
        assertEquals("EXACT", body.get("match").asText());
        assertEquals(1, body.get("securities").size());
        assertEquals("SMPL", body.get("securities").get(0).get("ticker").asText());
        assertTrue(body.get("securities").get(0).has("isin"));
        assertTrue(body.get("securities").get(0).has("name"));
    }

    @Test
    void ambiguousNameReturnsCandidates() throws Exception {
        JsonNode body = MAPPER.readTree(find("Training").content());

        assertEquals("CANDIDATES", body.get("match").asText());
        assertEquals(3, body.get("securities").size());
        assertEquals(AmbiguityGuard.ASK_FIRST, body.get("nextStep").asText());
    }

    @Test
    void exactMatchHasNoNextStep() throws Exception {
        assertFalse(MAPPER.readTree(find("TRNC").content()).has("nextStep"));
    }

    @Test
    void unknownAndEmptyQueryAreNotFoundNotError() throws Exception {
        for (String query : new String[]{"zzz-nothing", ""}) {
            ToolResult result = find(query);
            assertFalse(result.error(), query);
            JsonNode body = MAPPER.readTree(result.content());
            assertEquals("NOT_FOUND", body.get("match").asText());
            assertEquals(0, body.get("securities").size());
        }
    }

    @Test
    void missingQueryIsToolError() {
        assertTrue(find(null).error());
    }
}
