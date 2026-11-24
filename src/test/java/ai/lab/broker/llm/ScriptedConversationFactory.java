package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Task 8: a scripted model in place of Claude or Ollama. The test lists the model's turns in advance
 * ({@link #callsTool}, {@link #answers}, {@link #fails}); every {@code send()} of any conversation takes the next one.
 * Each created conversation records what the model would have seen: the system prompt, tool definitions,
 * user messages and tool results.
 */
public final class ScriptedConversationFactory implements ToolConversationFactory {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Deque<Object> script = new ArrayDeque<>();
    private final List<Conversation> conversations = new ArrayList<>();

    public ScriptedConversationFactory callsTool(String name, String argumentsJson) {
        try {
            JsonNode arguments = JSON.readTree(argumentsJson);
            script.add(new ToolConversation.Turn(
                    List.of(new ToolConversation.ToolCall("call-" + script.size(), name, arguments)), response("")));
        } catch (Exception e) {
            throw new IllegalArgumentException("Bad arguments JSON: " + argumentsJson, e);
        }
        return this;
    }

    public ScriptedConversationFactory answers(String text) {
        script.add(new ToolConversation.Turn(List.of(), response(text)));
        return this;
    }

    /** The next send() throws, as a provider would on a network error, 429 or 5xx. */
    public ScriptedConversationFactory fails(Exception error) {
        script.add(error);
        return this;
    }

    public void reset() {
        script.clear();
        conversations.clear();
    }

    public List<Conversation> conversations() {
        return List.copyOf(conversations);
    }

    public Conversation lastConversation() {
        return conversations.getLast();
    }

    public int remainingTurns() {
        return script.size();
    }

    @Override
    public synchronized ToolConversation create(String system, ArrayNode tools) {
        Conversation conversation = new Conversation(system, tools.deepCopy());
        conversations.add(conversation);
        return conversation;
    }

    private synchronized Object nextTurn() {
        if (script.isEmpty()) {
            throw new IllegalStateException("The scripted model has no more turns");
        }
        return script.removeFirst();
    }

    private static LlmResponse response(String text) {
        return new LlmResponse(text, 100, 20, 5, text.isEmpty() ? "tool_use" : "end_turn", "0.0001");
    }

    /** One scripted conversation; everything it received is kept for assertions. */
    public final class Conversation implements ToolConversation {
        private final String system;
        private final ArrayNode tools;
        /** The history as the model would keep it: user messages (String), model turns (Turn), tool results. */
        private final List<Object> history = new ArrayList<>();

        private Conversation(String system, ArrayNode tools) {
            this.system = system;
            this.tools = tools;
        }

        public String system() {
            return system;
        }

        public ArrayNode tools() {
            return tools;
        }

        public List<String> userMessages() {
            return history.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }

        public List<ToolResult> toolResults() {
            return history.stream().filter(ToolResult.class::isInstance).map(ToolResult.class::cast).toList();
        }

        /** Everything the model was given in this conversation, as text: prompt, definitions, messages, results. */
        public String everythingSeen() {
            StringBuilder seen = new StringBuilder(system).append('\n').append(tools);
            userMessages().forEach(message -> seen.append('\n').append(message));
            toolResults().forEach(result -> seen.append('\n').append(result.content()));
            return seen.toString();
        }

        @Override
        public void addUserMessage(String text) {
            history.add(text);
        }

        @Override
        public Turn send() throws Exception {
            Object next = nextTurn();
            if (next instanceof Exception error) {
                throw error;
            }
            history.add(next);
            return (Turn) next;
        }

        @Override
        public void addToolResults(List<ToolResult> results) {
            history.addAll(results);
        }

        @Override
        public int size() {
            return history.size();
        }

        @Override
        public void truncate(int size) {
            history.subList(size, history.size()).clear();
        }

        @Override
        public boolean dropOldestExchange() {
            return false;
        }

        @Override
        public String name() {
            return "scripted/test";
        }
    }
}
