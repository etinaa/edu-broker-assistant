package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

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
     * Response to a request with tools. content — the response blocks as is (text, tool_use): they must be added
     * back to the history as a whole assistant message, otherwise tool_result has nothing to refer to.
     */
    public record ToolTurn(JsonNode content, LlmResponse response) {
    }

    /**
     * toolChoice — the tool_choice field as is (auto/any/tool/none, disable_parallel_tool_use); null — omit it.
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
            throw new IllegalStateException("Claude " + response.statusCode() + ": " + Payloads.errorBody(response.body()));
        }
        Payloads.LOG.debug("Claude ← {}", response.body());

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
        Payloads.LOG.debug("Claude → {}", json);
        return HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    /**
     * content — an array of blocks; the text is joined from blocks of type=text.
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
