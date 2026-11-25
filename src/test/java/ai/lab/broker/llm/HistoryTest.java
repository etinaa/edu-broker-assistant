package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Dropping whole exchanges in both provider formats. */
class HistoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ArrayNode messages(String json) throws Exception {
        return (ArrayNode) JSON.readTree(json);
    }

    private static List<String> contents(ArrayNode messages) {
        List<String> result = new ArrayList<>();
        for (JsonNode message : messages) {
            result.add(message.path("role").asText() + ":" + message.path("content"));
        }
        return result;
    }

    @Test
    void claudeExchangeIsDroppedWithToolUseAndToolResult() throws Exception {
        ArrayNode messages = messages("""
                [
                  {"role":"user","content":"find Training"},
                  {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"find_security","input":{}}]},
                  {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"{}"}]},
                  {"role":"assistant","content":[{"type":"text","text":"Which one?"}]},
                  {"role":"user","content":"TRNC"},
                  {"role":"assistant","content":[{"type":"text","text":"OK"}]}
                ]""");

        assertTrue(History.dropOldestExchange(messages));

        assertEquals(2, messages.size());
        assertEquals("user:\"TRNC\"", contents(messages).getFirst());
    }

    @Test
    void ollamaExchangeIsDroppedWithToolMessages() throws Exception {
        ArrayNode messages = messages("""
                [
                  {"role":"user","content":"find Training"},
                  {"role":"assistant","content":"","tool_calls":[{"function":{"name":"find_security","arguments":{}}}]},
                  {"role":"tool","tool_name":"find_security","content":"{}"},
                  {"role":"assistant","content":"Which one?"},
                  {"role":"user","content":"TRNC"}
                ]""");

        assertTrue(History.dropOldestExchange(messages));

        assertEquals(List.of("user:\"TRNC\""), contents(messages));
    }

    @Test
    void latestExchangeIsNeverDropped() throws Exception {
        ArrayNode messages = messages("""
                [
                  {"role":"user","content":"hi"},
                  {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"get_quote","input":{}}]},
                  {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"{}"}]}
                ]""");

        assertFalse(History.dropOldestExchange(messages));
        assertEquals(3, messages.size());
    }

    @Test
    void truncateKeepsTheFirstMessages() throws Exception {
        ArrayNode messages = messages("""
                [{"role":"user","content":"a"},{"role":"assistant","content":"b"},{"role":"user","content":"c"}]""");

        History.truncate(messages, 1);

        assertEquals(List.of("user:\"a\""), contents(messages));
    }

    @Test
    void truncateBeyondTheHistoryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> History.truncate(JSON.createArrayNode(), 1));
    }

    @Test
    void claudeSeenHasUserTextAndSuccessfulResultsOnly() throws Exception {
        ArrayNode messages = messages("""
                [
                  {"role":"user","content":"buy Tesla"},
                  {"role":"assistant","content":[{"type":"text","text":"Did you mean TSLA?"},
                                                  {"type":"tool_use","id":"t1","name":"get_quote","input":{}}]},
                  {"role":"user","content":[
                    {"type":"tool_result","tool_use_id":"t1","content":"Unknown ticker TSLA","is_error":true},
                    {"type":"tool_result","tool_use_id":"t2","content":"{\\"ticker\\":\\"SMPL\\"}"}]}
                ]""");

        ToolConversation.Seen seen = History.seen(messages);

        assertEquals(List.of("buy Tesla"), seen.userMessages());
        assertEquals(List.of("{\"ticker\":\"SMPL\"}"), seen.toolResults());
    }

    @Test
    void ollamaSeenSkipsAssistantAndErrorResults() throws Exception {
        ArrayNode messages = messages("""
                [
                  {"role":"user","content":"buy Tesla"},
                  {"role":"assistant","content":"Did you mean TSLA?"},
                  {"role":"tool","tool_name":"get_quote","content":"ERROR: Unknown ticker TSLA"},
                  {"role":"tool","tool_name":"find_security","content":"{\\"ticker\\":\\"SMPL\\"}"}
                ]""");

        ToolConversation.Seen seen = History.seen(messages);

        assertEquals(List.of("buy Tesla"), seen.userMessages());
        assertEquals(List.of("{\"ticker\":\"SMPL\"}"), seen.toolResults());
    }

    @Test
    void emptyHistoryHasNothingToDrop() {
        assertFalse(History.dropOldestExchange(JSON.createArrayNode()));
    }
}
