package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool conversation on Ollama: a call request is message.tool_calls (done_reason is still a plain "stop", so only
 * the presence of tool_calls signals a call); results are one message with role tool per call.
 */
public final class OllamaToolConversation implements ToolConversation {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OllamaClient ollama;
    private final String system;
    private final ArrayNode tools;
    private final double temperature;
    private final ArrayNode messages = JSON.createArrayNode();

    /** tools — definitions in Claude format; they are converted to Ollama format here. */
    public OllamaToolConversation(OllamaClient ollama, String system, ArrayNode claudeTools, double temperature) {
        this.ollama = ollama;
        this.system = system;
        this.temperature = temperature;
        this.tools = JSON.createArrayNode();
        claudeTools.forEach(tool -> tools.add(toOllamaTool(tool)));
    }

    /** The same tool in Ollama format: wrapped in type=function, with input_schema renamed to parameters. */
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
        return new Turn(calls, turn.response(), "length".equals(turn.response().stopReason()));
    }

    /**
     * One message per call, in the same order as tool_calls; Ollama needs no call id, only tool_name.
     * The tool role has no error flag, so the model can see an error only from the text.
     */
    @Override
    public void addToolResults(List<ToolResult> results) {
        for (ToolResult result : results) {
            messages.add(JSON.createObjectNode()
                    .put("role", "tool")
                    .put("tool_name", result.call().name())
                    .put("content", result.error() ? History.OLLAMA_ERROR_PREFIX + result.content() : result.content()));
        }
    }

    @Override
    public Seen seen() {
        return History.seen(messages);
    }

    @Override
    public int size() {
        return messages.size();
    }

    @Override
    public boolean dropOldestExchange() {
        return History.dropOldestExchange(messages);
    }

    @Override
    public void truncate(int size) {
        History.truncate(messages, size);
    }

    @Override
    public String name() {
        return ollama.name();
    }
}
