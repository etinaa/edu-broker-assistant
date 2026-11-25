package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Provider-independent tool conversation (carried over from lesson 1.4). An implementation keeps the history in
 * its API's format (tool_use/tool_result blocks for Claude, tool_calls and the tool role for Ollama), while the
 * loop sees only call requests and results.
 * <p>
 * Unlike lesson 1.4, a conversation outlives a single question: it is the user's session. So the first question
 * is not passed to the constructor; every new message is added with {@link #addUserMessage(String)}.
 */
public interface ToolConversation {

    /** The model's request to call a tool; id is null if the provider did not send one. */
    record ToolCall(String id, String name, JsonNode arguments) {
        @Override
        public String toString() {
            return name + arguments;
        }
    }

    /** error — the result describes an error (sent as is_error: true to Claude, as a text marker to Ollama). */
    record ToolResult(ToolCall call, String content, boolean error) {
        public static ToolResult ok(ToolCall call, String content) {
            return new ToolResult(call, content, false);
        }

        public static ToolResult error(ToolCall call, String message) {
            return new ToolResult(call, message, true);
        }
    }

    /** What the model was given and may rely on: user messages as typed, tool results as returned (JSON). */
    record Seen(List<String> userMessages, List<String> toolResults) {}

    /** The model's reply on one step: empty calls means the model answered with text and the loop is done. */
    record Turn(List<ToolCall> calls, LlmResponse response) {}

    /** Adds a new user message to the session history. */
    void addUserMessage(String text);

    /** Sends the history to the model; the model's reply is added to the history as a whole. */
    Turn send() throws Exception;

    /** Adds the results of all calls of one step: in one message or one per call, as the provider requires. */
    void addToolResults(List<ToolResult> results);

    /**
     * The user's messages and the successful tool results still in the history: identifiers the model passes to tools
     * must come from here ({@code GroundedExecutor}). The model's own replies and error results are not included.
     */
    Seen seen();

    /** Number of messages in the history; useful for the session history limit. */
    int size();

    /**
     * Removes the oldest exchange: a user message with all the model's replies, tool calls and results that
     * followed it. Returns false and changes nothing if only the latest exchange is left.
     */
    boolean dropOldestExchange();

    /** Removes every message after the first {@code size}: rolls the history back to an earlier {@link #size()}. */
    void truncate(int size);

    /** Provider and model name, for traces. */
    String name();
}
