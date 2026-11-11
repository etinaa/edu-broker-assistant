package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real HTTP round-trips between {@link OllamaClient} and a local fake Ollama server (JDK HttpServer),
 * so no running Ollama is needed.
 */
class OllamaClientIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;

    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    private volatile int responseStatus = 200;
    private volatile String responseBody = TEXT_ANSWER;

    private static final String TEXT_ANSWER = """
            {"model":"qwen2.5:14b","message":{"role":"assistant","content":"Привет!"},
             "done":true,"done_reason":"stop","prompt_eval_count":42,"eval_count":7}""";

    private static final String TOOL_CALL_ANSWER = """
            {"model":"qwen2.5:14b","message":{"role":"assistant","content":"",
              "tool_calls":[{"function":{"name":"find_security","arguments":{"query":"TRNC"}}}]},
             "done":true,"done_reason":"stop","prompt_eval_count":100,"eval_count":15}""";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static ArrayNode messages(String... userTexts) {
        ArrayNode arr = JSON.createArrayNode();
        for (String t : userTexts) {
            arr.add(JSON.createObjectNode().put("role", "user").put("content", t));
        }
        return arr;
    }

    private static ArrayNode tools() {
        ArrayNode arr = JSON.createArrayNode();
        arr.addObject().put("type", "function").putObject("function").put("name", "find_security");
        return arr;
    }

    private JsonNode sentBody() throws Exception {
        return JSON.readTree(requestBody.get());
    }

    // ---- request ----

    @Test
    void sendsPostToApiChatWithJson() throws Exception {
        new OllamaClient(baseUrl, "qwen2.5:14b", 16384)
                .chatWithTools("sys", messages("hi"), tools(), 0.0);

        assertEquals("POST", method.get());
        assertEquals("/api/chat", path.get());
        assertEquals("application/json", contentType.get());
    }

    @Test
    void requestBodyContainsModelNonStreamingOptionsAndTools() throws Exception {
        new OllamaClient(baseUrl, "qwen2.5:14b", 16384)
                .chatWithTools("sys", messages("hi"), tools(), 0.25);

        JsonNode body = sentBody();
        assertEquals("qwen2.5:14b", body.path("model").asText());
        assertFalse(body.path("stream").asBoolean(true));
        assertEquals(0.25, body.path("options").path("temperature").asDouble());
        assertEquals(16384, body.path("options").path("num_ctx").asInt());
        assertEquals("find_security", body.path("tools").get(0).path("function").path("name").asText());
    }

    @Test
    void systemPromptIsFirstMessageFollowedByHistoryInOrder() throws Exception {
        new OllamaClient(baseUrl, "m", null)
                .chatWithTools("Ты брокер", messages("первый", "второй"), tools(), 0.0);

        JsonNode msgs = sentBody().path("messages");
        assertEquals(3, msgs.size());
        assertEquals("system", msgs.get(0).path("role").asText());
        assertEquals("Ты брокер", msgs.get(0).path("content").asText());
        assertEquals("первый", msgs.get(1).path("content").asText());
        assertEquals("второй", msgs.get(2).path("content").asText());
    }

    @Test
    void nullNumCtxIsOmittedFromOptions() throws Exception {
        new OllamaClient(baseUrl, "m", null).chatWithTools("s", messages("hi"), tools(), 0.0);

        JsonNode options = sentBody().path("options");
        assertFalse(options.has("num_ctx"));
        assertTrue(options.has("temperature"));
    }

    @Test
    void doesNotMutateCallersMessages() throws Exception {
        ArrayNode history = messages("hi");

        new OllamaClient(baseUrl, "m", null).chatWithTools("sys", history, tools(), 0.0);

        assertEquals(1, history.size());
    }

    // ---- response ----

    @Test
    void parsesTextAnswerAndUsage() throws Exception {
        OllamaClient.ToolTurn turn = new OllamaClient(baseUrl, "m", null)
                .chatWithTools("s", messages("hi"), tools(), 0.0);

        LlmResponse r = turn.response();
        assertEquals("Привет!", r.text());
        assertEquals(42, r.inputTokens());
        assertEquals(7, r.outputTokens());
        assertEquals("stop", r.stopReason());
        assertEquals("0", r.cost());
        assertTrue(r.latencyMs() >= 0);
        assertEquals("assistant", turn.message().path("role").asText());
        assertTrue(turn.message().path("tool_calls").isMissingNode());
    }

    @Test
    void returnsAssistantMessageWithToolCallsAsIs() throws Exception {
        responseBody = TOOL_CALL_ANSWER;

        OllamaClient.ToolTurn turn = new OllamaClient(baseUrl, "m", null)
                .chatWithTools("s", messages("найди TRNC"), tools(), 0.0);

        JsonNode call = turn.message().path("tool_calls").get(0).path("function");
        assertEquals("find_security", call.path("name").asText());
        assertEquals("TRNC", call.path("arguments").path("query").asText());
        assertEquals("", turn.response().text());
        assertEquals(100, turn.response().inputTokens());
    }

    @Test
    void missingUsageFieldsDefaultToZero() throws Exception {
        responseBody = "{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}";

        LlmResponse r = new OllamaClient(baseUrl, "m", null)
                .chatWithTools("s", messages("hi"), tools(), 0.0).response();

        assertEquals("ok", r.text());
        assertEquals(0, r.inputTokens());
        assertEquals(0, r.outputTokens());
    }

    // ---- errors ----

    @Test
    void non200ThrowsWithStatusAndBody() {
        responseStatus = 500;
        responseBody = "{\"error\":\"model not found\"}";
        OllamaClient client = new OllamaClient(baseUrl, "m", null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.chatWithTools("s", messages("hi"), tools(), 0.0));

        assertTrue(e.getMessage().contains("500"), e.getMessage());
        assertTrue(e.getMessage().contains("model not found"), e.getMessage());
    }

    @Test
    void notFoundStatusAlsoThrows() {
        responseStatus = 404;
        responseBody = "not found";
        OllamaClient client = new OllamaClient(baseUrl, "m", null);

        assertThrows(IllegalStateException.class,
                () -> client.chatWithTools("s", messages("hi"), tools(), 0.0));
    }

    @Test
    void malformedJsonResponseThrows() {
        responseBody = "<html>not json</html>";
        OllamaClient client = new OllamaClient(baseUrl, "m", null);

        assertThrows(Exception.class, () -> client.chatWithTools("s", messages("hi"), tools(), 0.0));
    }

    @Test
    void unreachableServerThrows() {
        server.stop(0);
        OllamaClient client = new OllamaClient(baseUrl, "m", null);

        assertThrows(IOException.class, () -> client.chatWithTools("s", messages("hi"), tools(), 0.0));
    }

    // ---- misc ----

    @Test
    void nameIncludesModel() {
        assertEquals("ollama/qwen2.5:14b", new OllamaClient(baseUrl, "qwen2.5:14b", null).name());
    }

    // ---- through OllamaToolConversation ----

    @Test
    void conversationRoundTripReturnsToolCallsAndKeepsHistory() throws Exception {
        responseBody = TOOL_CALL_ANSWER;
        ArrayNode claudeTools = JSON.createArrayNode();
        claudeTools.addObject().put("name", "find_security").put("description", "d")
                .putObject("input_schema").put("type", "object");
        OllamaToolConversation conv =
                new OllamaToolConversation(new OllamaClient(baseUrl, "m", null), "sys", claudeTools, 0.0);

        conv.addUserMessage("найди TRNC");
        ToolConversation.Turn turn = conv.send();

        assertEquals(1, turn.calls().size());
        assertEquals("find_security", turn.calls().getFirst().name());
        assertEquals("TRNC", turn.calls().getFirst().arguments().path("query").asText());
        assertEquals(2, conv.size());

        // The tool definition is sent translated to the Ollama format.
        JsonNode sentTool = sentBody().path("tools").get(0);
        assertEquals("function", sentTool.path("type").asText());
        assertEquals("find_security", sentTool.path("function").path("name").asText());
        assertEquals("object", sentTool.path("function").path("parameters").path("type").asText());
    }
}
