package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.List;

/**
 * History trimming shared by the providers. An exchange starts with a user message whose content is plain text
 * (Claude's tool_result messages also have role user, but their content is an array of blocks; Ollama's results
 * have role tool). Dropping whole exchanges keeps every tool call next to its result, and the history keeps
 * starting with a user message, as both APIs require.
 */
final class History {

    /** Ollama has no error flag for tool results, so an error is marked in the text. */
    static final String OLLAMA_ERROR_PREFIX = "ERROR: ";

    private History() {
    }

    /** Removes the oldest exchange; false if there is only one exchange left, which is never dropped. */
    static boolean dropOldestExchange(ArrayNode messages) {
        int nextExchange = -1;
        for (int i = 1; i < messages.size(); i++) {
            if (startsExchange(messages.get(i))) {
                nextExchange = i;
                break;
            }
        }
        if (nextExchange < 0) {
            return false;
        }
        for (int i = 0; i < nextExchange; i++) {
            messages.remove(0);
        }
        return true;
    }

    /** Keeps the first {@code size} messages; a size outside 0..messages.size() is a bug in the caller. */
    static void truncate(ArrayNode messages, int size) {
        if (size < 0 || size > messages.size()) {
            throw new IllegalArgumentException("Cannot truncate " + messages.size() + " messages to " + size);
        }
        while (messages.size() > size) {
            messages.remove(messages.size() - 1);
        }
    }

    /**
     * User messages and successful tool results in the history: what the model may take identifiers from. The model's
     * own text is left out (it could name a ticker it made up), and so are error results (they may echo the bad value).
     * Claude marks errors with is_error; Ollama results start with {@link #OLLAMA_ERROR_PREFIX}.
     */
    static ToolConversation.Seen seen(ArrayNode messages) {
        List<String> userMessages = new ArrayList<>();
        List<String> toolResults = new ArrayList<>();
        for (JsonNode message : messages) {
            String role = message.path("role").asText();
            JsonNode content = message.path("content");
            if ("user".equals(role) && content.isTextual()) {
                userMessages.add(content.asText());
            } else if ("user".equals(role) && content.isArray()) {
                for (JsonNode block : content) {
                    if ("tool_result".equals(block.path("type").asText()) && !block.path("is_error").asBoolean()) {
                        toolResults.add(block.path("content").asText());
                    }
                }
            } else if ("tool".equals(role) && !content.asText().startsWith(OLLAMA_ERROR_PREFIX)) {
                toolResults.add(content.asText());
            }
        }
        return new ToolConversation.Seen(userMessages, toolResults);
    }

    private static boolean startsExchange(JsonNode message) {
        return "user".equals(message.path("role").asText()) && message.path("content").isTextual();
    }
}
