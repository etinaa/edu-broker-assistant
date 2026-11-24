package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * History trimming shared by the providers. An exchange starts with a user message whose content is plain text
 * (Claude's tool_result messages also have role user, but their content is an array of blocks; Ollama's results
 * have role tool). Dropping whole exchanges keeps every tool call next to its result, and the history keeps
 * starting with a user message, as both APIs require.
 */
final class History {

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

    private static boolean startsExchange(JsonNode message) {
        return "user".equals(message.path("role").asText()) && message.path("content").isTextual();
    }
}
