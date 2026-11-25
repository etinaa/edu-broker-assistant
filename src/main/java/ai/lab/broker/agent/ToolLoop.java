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
 * Цикл «пока модель просит вызов — выполняем» (перенесено из урока 1.4). Шаг — один запрос к модели;
 * потолок шагов обязателен, иначе модель может вызывать инструменты бесконечно.
 */
@Slf4j
public final class ToolLoop {

    /** What the model sees instead of the exception text, which may hold internal details. */
    static final String TOOL_FAILED = "Internal error in tool %s. Tell the user the operation failed; do not retry it.";

    /** Один шаг цикла: что вернули инструменты (пусто — финальный ответ) и сколько стоил запрос к модели. */
    public record Step(List<ToolResult> results, LlmResponse response) {}

    /** answer — текст последнего ответа; limitReached — модель всё ещё просила вызовы, когда шаги кончились. */
    public record Result(List<Step> steps, String answer, boolean limitReached) {}

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
                return new Result(steps, turn.response().text(), false);
            }
            // Все вызовы одного ответа выполняем и возвращаем вместе — следующий запрос увидит их разом.
            List<ToolResult> results = new ArrayList<>();
            for (ToolCall call : turn.calls()) {
                results.add(execute(executor, call));
            }
            conversation.addToolResults(results);
            steps.add(new Step(results, turn.response()));
        }
        return new Result(steps, steps.getLast().response().text(), true);
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
