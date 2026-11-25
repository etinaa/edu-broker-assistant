package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Local model via Ollama (POST /api/chat): only the request with tools; the server address, model and context window come from configuration.
 */
public class OllamaClient {

    private static final String NO_COST = "0";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String model;
    private final Integer numCtx;

    /** baseUrl — the Ollama server address (tests may pass a fake one); numCtx — null to use the default. */
    public OllamaClient(String baseUrl, String model, Integer numCtx) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.numCtx = numCtx;
    }

    /**
     * Response to a request with tools. message — the assistant message as is (content, thinking, tool_calls):
     * it goes back into the history as a whole, before the messages with role tool.
     */
    public record ToolTurn(JsonNode message, LlmResponse response) {}

    /** Messages are a raw JSON tree in Ollama format; system is added as the first message. */
    public ToolTurn chatWithTools(String system, ArrayNode messages, ArrayNode tools, double temperature)
            throws Exception {
        ArrayNode allMessages = JSON.createArrayNode()
                .add(JSON.createObjectNode().put("role", "system").put("content", system));
        allMessages.addAll(messages);

        Map<String, Object> options = new HashMap<>();
        options.put("temperature", temperature);
        if (numCtx != null) {
            options.put("num_ctx", numCtx);
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "stream", false,
                "options", options,
                "messages", allMessages,
                "tools", tools);

        long start = System.nanoTime();
        HttpResponse<String> response = http.send(buildRequest(body), HttpResponse.BodyHandlers.ofString());
        long latency = Duration.ofNanos(System.nanoTime() - start).toMillis();
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Ollama " + response.statusCode() + ": " + Payloads.errorBody(response.body()));
        }
        Payloads.LOG.debug("Ollama ← {}", response.body());

        JsonNode root = JSON.readTree(response.body());
        JsonNode message = root.path("message");
        return new ToolTurn(message, new LlmResponse(
                message.path("content").asText(),
                root.path("prompt_eval_count").asInt(),
                root.path("eval_count").asInt(),
                latency,
                root.path("done_reason").asText(),
                NO_COST));
    }

    public String name() {
        return "ollama/" + model;
    }

    private HttpRequest buildRequest(Map<String, Object> body) throws Exception {
        String json = JSON.writeValueAsString(body);
        Payloads.LOG.debug("Ollama → {}", json);
        return HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}
