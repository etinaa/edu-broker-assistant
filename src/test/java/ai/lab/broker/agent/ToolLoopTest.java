package ai.lab.broker.agent;

import ai.lab.broker.llm.LlmResponse;
import ai.lab.broker.llm.ToolConversation;
import ai.lab.broker.llm.ToolConversation.Turn;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolLoopTest {

    private static final ToolConversation.ToolCall CALL =
            new ToolConversation.ToolCall("c1", "get_quote", JsonNodeFactory.instance.objectNode());

    @Test
    void failedToolGivesTheModelAGenericErrorWithoutExceptionDetails() throws Exception {
        TwoStepConversation conversation = new TwoStepConversation();

        ToolLoop.Result result = ToolLoop.run(conversation, call -> {
            throw new IllegalStateException("jdbc:postgresql://db.internal/broker password=secret");
        }, 3);

        ToolConversation.ToolResult toolResult = result.steps().getFirst().results().getFirst();
        assertTrue(toolResult.error());
        assertEquals(ToolLoop.TOOL_FAILED.formatted("get_quote"), toolResult.content());
        assertFalse(conversation.sent.toString().contains("secret"));
        assertEquals("done", result.answer());
    }

    @Test
    void nonPositiveMaxStepsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolLoop.run(new TwoStepConversation(), call -> null, 0));
    }

    @Test
    void callsOfACutOffReplyAreNotRunAndTheModelIsAskedToRepeatThem() throws Exception {
        TwoStepConversation conversation = new TwoStepConversation(
                new Turn(List.of(CALL), TwoStepConversation.response(""), true),
                new Turn(List.of(), TwoStepConversation.response("done")));
        List<ToolConversation.ToolCall> executed = new ArrayList<>();

        ToolLoop.Result result = ToolLoop.run(conversation, call -> {
            executed.add(call);
            return ToolConversation.ToolResult.ok(call, "{}");
        }, 3);

        assertTrue(executed.isEmpty());
        assertEquals(List.of(ToolConversation.ToolResult.error(CALL, ToolLoop.CALL_TRUNCATED)), conversation.sent);
        assertEquals("done", result.answer());
        assertFalse(result.truncated());
    }

    @Test
    void cutOffFinalAnswerIsMarkedTruncated() throws Exception {
        TwoStepConversation conversation = new TwoStepConversation(
                new Turn(List.of(), TwoStepConversation.response("The portfolio holds"), true));

        ToolLoop.Result result = ToolLoop.run(conversation, call -> null, 3);

        assertTrue(result.truncated());
        assertFalse(result.limitReached());
        assertEquals("The portfolio holds", result.answer());
    }

    /** Plays the scripted turns; by default asks for one tool call, then answers "done". Keeps the tool results sent. */
    private static final class TwoStepConversation implements ToolConversation {
        private final Deque<Turn> script;
        private final List<ToolResult> sent = new ArrayList<>();

        TwoStepConversation() {
            this(new Turn(List.of(CALL), response("")), new Turn(List.of(), response("done")));
        }

        TwoStepConversation(Turn... turns) {
            script = new ArrayDeque<>(List.of(turns));
        }

        private static LlmResponse response(String text) {
            return new LlmResponse(text, 1, 1, 1, "end_turn", "0");
        }

        @Override
        public void addUserMessage(String text) {
        }

        @Override
        public Turn send() {
            return script.removeFirst();
        }

        @Override
        public void addToolResults(List<ToolResult> results) {
            sent.addAll(results);
        }

        @Override
        public Seen seen() {
            return new Seen(List.of(), List.of());
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public boolean dropOldestExchange() {
            return false;
        }

        @Override
        public void truncate(int size) {
        }

        @Override
        public String name() {
            return "two-step";
        }
    }
}
