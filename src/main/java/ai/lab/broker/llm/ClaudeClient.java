package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class ClaudeClient {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final BigDecimal TOKENS_PER_MILLION = BigDecimal.valueOf(1_000_000);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String model;
    private final String apiKey;
    private final int maxTokens;
    private final BigDecimal inputPricePerMillion;
    private final BigDecimal outputPricePerMillion;

    public ClaudeClient(String model, String apiKey, int maxTokens,
                        BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion) {
        this.model = model;
        this.apiKey = apiKey;
        this.maxTokens = maxTokens;
        this.inputPricePerMillion = inputPricePerMillion;
        this.outputPricePerMillion = outputPricePerMillion;
    }

    /**
     * Ответ на запрос с инструментами. content — блоки ответа как есть (text, tool_use): их целиком
     * нужно вернуть в историю assistant-сообщением, иначе tool_result не к чему будет привязать.
     */
    public record ToolTurn(JsonNode content, LlmResponse response) {
    }

    /**
     * toolChoice — поле tool_choice как есть (auto/any/tool/none, disable_parallel_tool_use); null — не передавать.
     */
    public ToolTurn chatWithTools(String system, ArrayNode messages, ArrayNode tools, JsonNode toolChoice,
                                  double temperature) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
                "model", model,
                "max_tokens", maxTokens,
                "system", system,
                "temperature", temperature,
                "tools", tools,
                "messages", messages));
        if (toolChoice != null) {
            body.put("tool_choice", toolChoice);
        }

        long start = System.currentTimeMillis();
        HttpResponse<String> response = http.send(buildRequest(body), HttpResponse.BodyHandlers.ofString());
        long latency = System.currentTimeMillis() - start;
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Claude " + response.statusCode() + ": " + response.body());
        }
        log.debug("Claude ← {}", response.body());

        JsonNode root = JSON.readTree(response.body());
        int inputTokens = root.path("usage").path("input_tokens").asInt();
        int outputTokens = root.path("usage").path("output_tokens").asInt();
        return new ToolTurn(root.path("content"), new LlmResponse(
                extractText(root.path("content")),
                inputTokens,
                outputTokens,
                latency,
                root.path("stop_reason").asText(),
                calculateCost(inputTokens, outputTokens)));
    }

    public String name() {
        return "claude/" + model;
    }

    private HttpRequest buildRequest(Map<String, Object> body) throws Exception {
        String json = JSON.writeValueAsString(body);
        log.debug("Claude → {}", json);
        return HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    /**
     * content — массив блоков; текст собирается из блоков type=text.
     */
    private static String extractText(JsonNode contentBlocks) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : contentBlocks) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        return text.toString();
    }

    private String calculateCost(int inputTokens, int outputTokens) {
        return BigDecimal.valueOf(inputTokens)
                .multiply(inputPricePerMillion)
                .add(BigDecimal.valueOf(outputTokens)
                        .multiply(outputPricePerMillion))
                .divide(TOKENS_PER_MILLION)
                .toPlainString();
    }
}
