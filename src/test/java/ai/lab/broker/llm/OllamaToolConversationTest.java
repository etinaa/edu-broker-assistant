package ai.lab.broker.llm;

import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;
import ai.lab.broker.llm.ToolConversation.Turn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OllamaToolConversationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LlmResponse RESPONSE = new LlmResponse("", 10, 5, 100, "stop", "0");

    /** Snapshot of every chatWithTools call (the real arrays are mutated afterwards, so copy them). */
    private record Call(String system, ArrayNode messages, ArrayNode tools, double temperature) {}

    private OllamaClient client;
    private final List<Call> calls = new ArrayList<>();
    private ObjectNode nextMessage;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(OllamaClient.class);
        nextMessage = assistantText("ok");
        when(client.chatWithTools(anyString(), any(), any(), anyDouble())).thenAnswer(inv -> {
            calls.add(new Call(inv.getArgument(0),
                    ((ArrayNode) inv.getArgument(1)).deepCopy(),
                    ((ArrayNode) inv.getArgument(2)).deepCopy(),
                    inv.getArgument(3)));
            return new OllamaClient.ToolTurn(nextMessage, RESPONSE);
        });
        when(client.name()).thenReturn("ollama/test-model");
    }

    private static ArrayNode claudeTools() {
        ArrayNode tools = JSON.createArrayNode();
        ObjectNode tool = tools.addObject()
                .put("name", "find_security")
                .put("description", "Find a security");
        ObjectNode schema = tool.putObject("input_schema").put("type", "object");
        schema.putObject("properties").putObject("query").put("type", "string");
        return tools;
    }

    private OllamaToolConversation conversation() {
        return new OllamaToolConversation(client, "system prompt", claudeTools(), 0.3);
    }

    private static ObjectNode assistantText(String text) {
        return JSON.createObjectNode().put("role", "assistant").put("content", text);
    }

    private static ObjectNode assistantWithCalls(ObjectNode... functionCalls) {
        ObjectNode msg = assistantText("");
        ArrayNode arr = msg.putArray("tool_calls");
        for (ObjectNode f : functionCalls) {
            arr.addObject().set("function", f);
        }
        return msg;
    }

    private static ObjectNode function(String name, String argKey, String argValue) {
        ObjectNode f = JSON.createObjectNode().put("name", name);
        f.putObject("arguments").put(argKey, argValue);
        return f;
    }

    // ---- toOllamaTool ----

    @Test
    void toOllamaToolWrapsInFunctionAndRenamesInputSchema() {
        ObjectNode result = OllamaToolConversation.toOllamaTool(claudeTools().get(0));

        assertEquals("function", result.path("type").asText());
        JsonNode fn = result.path("function");
        assertEquals("find_security", fn.path("name").asText());
        assertEquals("Find a security", fn.path("description").asText());
        assertEquals("object", fn.path("parameters").path("type").asText());
        assertEquals("string", fn.path("parameters").path("properties").path("query").path("type").asText());
        assertFalse(fn.has("input_schema"));
        assertFalse(result.has("name"));
    }

    // ---- addUserMessage / size ----

    @Test
    void newConversationIsEmpty() {
        assertEquals(0, conversation().size());
    }

    @Test
    void addUserMessageAppendsUserRoleMessage() throws Exception {
        OllamaToolConversation conv = conversation();

        conv.addUserMessage("привет");

        assertEquals(1, conv.size());
        conv.send();
        JsonNode first = calls.getFirst().messages().get(0);
        assertEquals("user", first.path("role").asText());
        assertEquals("привет", first.path("content").asText());
    }

    // ---- send ----

    @Test
    void sendPassesSystemTemperatureAndTranslatedTools() throws Exception {
        OllamaToolConversation conv = conversation();
        conv.addUserMessage("hi");

        conv.send();

        Call call = calls.getFirst();
        assertEquals("system prompt", call.system());
        assertEquals(0.3, call.temperature());
        assertEquals(1, call.tools().size());
        assertEquals("function", call.tools().get(0).path("type").asText());
        assertEquals("find_security", call.tools().get(0).path("function").path("name").asText());
    }

    @Test
    void sendWithTextAnswerReturnsNoCallsAndStoresAssistantMessage() throws Exception {
        nextMessage = assistantText("Здравствуйте");
        OllamaToolConversation conv = conversation();
        conv.addUserMessage("hi");

        Turn turn = conv.send();

        assertTrue(turn.calls().isEmpty());
        assertSame(RESPONSE, turn.response());
        assertEquals(2, conv.size());
    }

    @Test
    void sendParsesToolCallsInOrder() throws Exception {
        nextMessage = assistantWithCalls(
                function("find_security", "query", "TRNC"),
                function("get_price", "ticker", "SMPL"));
        OllamaToolConversation conv = conversation();
        conv.addUserMessage("найди");

        Turn turn = conv.send();

        assertEquals(2, turn.calls().size());
        ToolCall first = turn.calls().get(0);
        assertEquals("find_security", first.name());
        assertEquals("TRNC", first.arguments().path("query").asText());
        assertEquals("get_price", turn.calls().get(1).name());
        assertEquals("SMPL", turn.calls().get(1).arguments().path("ticker").asText());
    }

    @Test
    void toolCallIdIsNullWhenProviderSendsNone() throws Exception {
        nextMessage = assistantWithCalls(function("find_security", "query", "X"));
        OllamaToolConversation conv = conversation();
        conv.addUserMessage("q");

        assertNull(conv.send().calls().getFirst().id());
    }

    @Test
    void toolCallIdIsTakenFromResponseWhenPresent() throws Exception {
        nextMessage = assistantWithCalls(function("find_security", "query", "X"));
        ((ObjectNode) nextMessage.path("tool_calls").get(0)).put("id", "call-1");
        OllamaToolConversation conv = conversation();
        conv.addUserMessage("q");

        assertEquals("call-1", conv.send().calls().getFirst().id());
    }

    @Test
    void sendKeepsAssistantMessageWithToolCallsInHistory() throws Exception {
        nextMessage = assistantWithCalls(function("find_security", "query", "TRNC"));
        OllamaToolConversation conv = conversation();
        conv.addUserMessage("найди");
        conv.send();

        nextMessage = assistantText("done");
        conv.send();

        JsonNode secondRequest = calls.get(1).messages();
        assertEquals(2, secondRequest.size());
        assertEquals("assistant", secondRequest.get(1).path("role").asText());
        assertEquals("find_security",
                secondRequest.get(1).path("tool_calls").get(0).path("function").path("name").asText());
    }

    @Test
    void sendPropagatesClientException() throws Exception {
        doThrow(new IllegalStateException("Ollama 500"))
                .when(client).chatWithTools(anyString(), any(), any(), anyDouble());
        OllamaToolConversation conv = conversation();
        conv.addUserMessage("hi");

        IllegalStateException e = assertThrows(IllegalStateException.class, conv::send);
        assertEquals("Ollama 500", e.getMessage());
        assertEquals(1, conv.size());
    }

    // ---- addToolResults ----

    @Test
    void addToolResultsAddsOneToolMessagePerResultInOrder() throws Exception {
        ToolCall c1 = new ToolCall(null, "find_security", JSON.createObjectNode());
        ToolCall c2 = new ToolCall(null, "get_price", JSON.createObjectNode());
        OllamaToolConversation conv = conversation();
        conv.addUserMessage("q");

        conv.addToolResults(List.of(ToolResult.ok(c1, "result-1"), ToolResult.ok(c2, "result-2")));

        assertEquals(3, conv.size());
        conv.send();
        ArrayNode sent = calls.getFirst().messages();
        assertEquals("tool", sent.get(1).path("role").asText());
        assertEquals("find_security", sent.get(1).path("tool_name").asText());
        assertEquals("result-1", sent.get(1).path("content").asText());
        assertEquals("tool", sent.get(2).path("role").asText());
        assertEquals("get_price", sent.get(2).path("tool_name").asText());
        assertEquals("result-2", sent.get(2).path("content").asText());
    }

    @Test
    void errorResultIsMarkedInTextBecauseOllamaHasNoErrorFlag() throws Exception {
        ToolCall call = new ToolCall(null, "find_security", JSON.createObjectNode());
        OllamaToolConversation conv = conversation();

        conv.addToolResults(List.of(ToolResult.error(call, "not found")));
        conv.send();

        JsonNode msg = calls.getFirst().messages().get(0);
        assertEquals("ERROR: not found", msg.path("content").asText());
        assertEquals("find_security", msg.path("tool_name").asText());
    }

    @Test
    void emptyResultsAddNothing() {
        OllamaToolConversation conv = conversation();

        conv.addToolResults(List.of());

        assertEquals(0, conv.size());
    }

    // ---- full cycle / misc ----

    @Test
    void fullToolCycleSendsUserAssistantToolHistoryOnSecondRequest() throws Exception {
        nextMessage = assistantWithCalls(function("find_security", "query", "TRNC"));
        OllamaToolConversation conv = conversation();
        conv.addUserMessage("найди TRNC");
        Turn first = conv.send();

        conv.addToolResults(List.of(ToolResult.ok(first.calls().getFirst(), "TRNC found")));
        nextMessage = assistantText("Нашёл TRNC");
        Turn second = conv.send();

        assertTrue(second.calls().isEmpty());
        assertEquals(4, conv.size());
        ArrayNode req = calls.get(1).messages();
        assertEquals(List.of("user", "assistant", "tool"),
                List.of(req.get(0).path("role").asText(), req.get(1).path("role").asText(),
                        req.get(2).path("role").asText()));
    }

    @Test
    void nameDelegatesToClient() {
        assertEquals("ollama/test-model", conversation().name());
    }
}
