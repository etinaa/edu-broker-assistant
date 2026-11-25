package ai.lab.broker.agent;

import ai.lab.broker.llm.LlmResponse;
import ai.lab.broker.llm.ToolConversation;
import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;
import ai.lab.broker.llm.ToolConversation.Turn;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The "while the model asks for a call, run it" loop (from lesson 1.4). A step is one request to the model;
 * the step limit is required, otherwise the model could call tools forever.
 */
@Slf4j
public final class ToolLoop {

    /** What the model sees instead of the exception text, which may hold internal details. */
    static final String TOOL_FAILED = "Internal error in tool %s. Tell the user the operation failed; do not retry it.";

    /** What the model gets for each call of a reply cut off by the output limit: the call is not run. */
    static final String CALL_TRUNCATED = "Your reply hit the output length limit, so this call may be incomplete and "
            + "was not run. Call the tool again in a shorter reply.";

    /** One step of the loop: what the tools returned (empty for the final answer) and what the model request cost. */
    public record Step(List<ToolResult> results, LlmResponse response) {}

    /**
     * answer is the text of the last reply; limitReached means the model still asked for calls when the steps ran out;
     * truncated means the final answer hit the output length limit and is incomplete.
     */
    public record Result(List<Step> steps, String answer, boolean limitReached, boolean truncated) {}

    private ToolLoop() {
    }

    /**
     * @throws ModelCallException   the model could not be asked; every other exception is a bug in our code
     * @throws InterruptedException the thread was interrupted while waiting for the model
     */
    public static Result run(ToolConversation conversation, Function<ToolCall, ToolResult> executor, int maxSteps)
            throws InterruptedException {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive: " + maxSteps);
        }
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < maxSteps; i++) {
            Turn turn = send(conversation);
            if (turn.calls().isEmpty()) {
                steps.add(new Step(List.of(), turn.response()));
                return new Result(steps, turn.response().text(), false, turn.truncated());
            }
            // All calls of one reply are run and returned together, so the next request sees them at once.
            // A cut-off reply runs none: its last call may have partial arguments (a quantity of 1 instead of 100),
            // and each call still needs a result, otherwise the provider rejects the history.
            List<ToolResult> results = new ArrayList<>();
            for (ToolCall call : turn.calls()) {
                results.add(turn.truncated() ? ToolResult.error(call, CALL_TRUNCATED) : execute(executor, call));
            }
            conversation.addToolResults(results);
            steps.add(new Step(results, turn.response()));
        }
        return new Result(steps, steps.getLast().response().text(), true, false);
    }

    /** Everything send() throws is the provider's failure, so it is marked apart from failures of our own code. */
    private static Turn send(ToolConversation conversation) throws InterruptedException {
        try {
            return conversation.send();
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelCallException(e);
        }
    }

    /**
     * A failed tool must not break the loop: the exception becomes an error result and the model decides what to
     * tell the user. The model and the trace get only a generic text; the exception with its details goes to the log.
     */
    private static ToolResult execute(Function<ToolCall, ToolResult> executor, ToolCall call) {
        try {
            return executor.apply(call);
        } catch (RuntimeException e) {
            log.error("Tool {} failed on call {}", call.name(), call.id(), e);
            return ToolResult.error(call, TOOL_FAILED.formatted(call.name()));
        }
    }
}
