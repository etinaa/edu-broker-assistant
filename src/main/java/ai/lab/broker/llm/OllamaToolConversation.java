package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Диалог с инструментами на Ollama: запрос на вызов — message.tool_calls (done_reason при этом обычный "stop",
 * так что признак вызова — только наличие tool_calls), результаты — по сообщению с ролью tool на каждый вызов.
 */
public final class OllamaToolConversation implements ToolConversation {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OllamaClient ollama;
    private final String system;
    private final ArrayNode tools;
    private final double temperature;
    private final ArrayNode messages = JSON.createArrayNode();

    /** tools — определения в формате Claude; переводятся в формат Ollama здесь. */
    public OllamaToolConversation(OllamaClient ollama, String system, ArrayNode claudeTools, double temperature) {
        this.ollama = ollama;
        this.system = system;
        this.temperature = temperature;
        this.tools = JSON.createArrayNode();
        claudeTools.forEach(tool -> tools.add(toOllamaTool(tool)));
    }

    /** Тот же инструмент в формате Ollama: обёртка type=function, а input_schema называется parameters. */
    static ObjectNode toOllamaTool(JsonNode claudeTool) {
        ObjectNode tool = JSON.createObjectNode().put("type", "function");
        tool.putObject("function")
                .put("name", claudeTool.path("name").asText())
                .put("description", claudeTool.path("description").asText())
                .set("parameters", claudeTool.path("input_schema"));
        return tool;
    }

    @Override
    public void addUserMessage(String text) {
        messages.add(JSON.createObjectNode().put("role", "user").put("content", text));
    }

    @Override
    public Turn send() throws Exception {
        OllamaClient.ToolTurn turn = ollama.chatWithTools(system, messages, tools, temperature);
        messages.add(turn.message());

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : turn.message().path("tool_calls")) {
            JsonNode function = call.path("function");
            String id = call.hasNonNull("id") ? call.path("id").asText() : null;
            calls.add(new ToolCall(id, function.path("name").asText(), function.path("arguments")));
        }
        return new Turn(calls, turn.response());
    }

    /**
     * По сообщению на вызов, в том же порядке, что и tool_calls; id вызова Ollama не требует — только tool_name.
     * Флага ошибки у роли tool нет, поэтому ошибка видна модели только по тексту.
     */
    @Override
    public void addToolResults(List<ToolResult> results) {
        for (ToolResult result : results) {
            messages.add(JSON.createObjectNode()
                    .put("role", "tool")
                    .put("tool_name", result.call().name())
                    .put("content", result.error() ? "ОШИБКА: " + result.content() : result.content()));
        }
    }

    @Override
    public int size() {
        return messages.size();
    }

    @Override
    public String name() {
        return ollama.name();
    }
}
