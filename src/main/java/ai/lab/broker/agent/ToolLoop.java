package ai.lab.broker.agent;

import ai.lab.broker.llm.LlmResponse;
import ai.lab.broker.llm.ToolConversation;
import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;
import ai.lab.broker.llm.ToolConversation.Turn;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Цикл «пока модель просит вызов — выполняем» (перенесено из урока 1.4). Шаг — один запрос к модели;
 * потолок шагов обязателен, иначе модель может вызывать инструменты бесконечно.
 */
public final class ToolLoop {

    /** Один шаг цикла: что вернули инструменты (пусто — финальный ответ) и сколько стоил запрос к модели. */
    public record Step(List<ToolResult> results, LlmResponse response) {}

    /** answer — текст последнего ответа; limitReached — модель всё ещё просила вызовы, когда шаги кончились. */
    public record Result(List<Step> steps, String answer, boolean limitReached) {}

    private ToolLoop() {
    }

    public static Result run(ToolConversation conversation, Function<ToolCall, ToolResult> executor, int maxSteps)
            throws Exception {
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < maxSteps; i++) {
            Turn turn = conversation.send();
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

    /**
     * Упавший инструмент не должен ронять цикл и показывать пользователю стек: исключение превращается в
     * результат-ошибку, и модель решает, что с ним делать.
     */
    private static ToolResult execute(Function<ToolCall, ToolResult> executor, ToolCall call) {
        try {
            return executor.apply(call);
        } catch (RuntimeException e) {
            return ToolResult.error(call, "Internal error in tool " + call.name() + ": " + e.getMessage());
        }
    }
}
