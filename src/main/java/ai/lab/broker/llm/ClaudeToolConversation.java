package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** Диалог с инструментами на Claude: запрос на вызов — блок tool_use, результаты — блоки tool_result в user-сообщении. */
public final class ClaudeToolConversation implements ToolConversation {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ClaudeClient claude;
    private final String system;
    private final ArrayNode tools;
    private final JsonNode toolChoice;
    private final double temperature;
    private final ArrayNode messages = JSON.createArrayNode();

    /**
     * tools — определения в формате Claude (name, description, input_schema).
     * toolChoice — поле tool_choice как есть; null — по умолчанию (auto).
     */
    public ClaudeToolConversation(ClaudeClient claude, String system, ArrayNode tools, JsonNode toolChoice,
                                  double temperature) {
        this.claude = claude;
        this.system = system;
        this.tools = tools;
        this.toolChoice = toolChoice;
        this.temperature = temperature;
    }

    @Override
    public void addUserMessage(String text) {
        messages.add(JSON.createObjectNode().put("role", "user").put("content", text));
    }

    @Override
    public Turn send() throws Exception {
        ClaudeClient.ToolTurn turn = claude.chatWithTools(system, messages, tools, toolChoice, temperature);
        messages.add(JSON.createObjectNode().put("role", "assistant").set("content", turn.content()));

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode block : turn.content()) {
            if ("tool_use".equals(block.path("type").asText())) {
                calls.add(new ToolCall(block.path("id").asText(), block.path("name").asText(), block.path("input")));
            }
        }
        return new Turn(calls, turn.response());
    }

    /** Все результаты шага — одним user-сообщением; tool_use_id связывает каждый с его вызовом. */
    @Override
    public void addToolResults(List<ToolResult> results) {
        ArrayNode content = JSON.createArrayNode();
        for (ToolResult result : results) {
            ObjectNode block = content.addObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", result.call().id())
                    .put("content", result.content());
            if (result.error()) {
                block.put("is_error", true);
            }
        }
        messages.add(JSON.createObjectNode().put("role", "user").set("content", content));
    }

    @Override
    public int size() {
        return messages.size();
    }

    @Override
    public String name() {
        return claude.name();
    }
}
