package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Локальная модель через Ollama (POST /api/chat). Перенесено из stage1-llm-basics и урезано до запроса с
 * инструментами; адрес сервера, модель и окно контекста — из конфигурации.
 */
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private static final String NO_COST = "0";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String model;
    private final Integer numCtx;

    /** baseUrl — адрес сервера Ollama (в тестах можно подставить фейковый); numCtx — null, чтобы взять умолчание. */
    public OllamaClient(String baseUrl, String model, Integer numCtx) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.numCtx = numCtx;
    }

    /**
     * Ответ на запрос с инструментами. message — assistant-сообщение как есть (content, thinking, tool_calls):
     * его целиком возвращаем в историю перед сообщениями с ролью tool.
     */
    public record ToolTurn(JsonNode message, LlmResponse response) {}

    /** Сообщения — сырое JSON-дерево в формате Ollama; system добавляется первым сообщением. */
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
            throw new IllegalStateException("Ollama " + response.statusCode() + ": " + response.body());
        }
        log.debug("Ollama ← {}", response.body());

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
        log.debug("Ollama → {}", json);
        return HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                .timeout(Duration.ofSeconds(300))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }
}
