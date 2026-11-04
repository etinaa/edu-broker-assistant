package ai.lab.broker.agent;

import ai.lab.broker.llm.ToolConversation.ToolResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Трасса обработки одного сообщения: шаги агента, вызовы инструментов, токены и стоимость.
 * Отдаётся через GET /traces/{id}; на этапе 4 её заменит OpenTelemetry.
 */
public record Trace(String id, String sessionId, String message, String model, List<Step> steps,
                    String answer, boolean limitReached, Instant createdAt) {

    /** Один запрос к модели и вызовы, которые он породил (пусто — финальный ответ). */
    public record Step(List<Call> calls, int inputTokens, int outputTokens, long latencyMs,
                       String stopReason, String cost) {

        public static Step from(ToolLoop.Step step) {
            List<Call> calls = step.results().stream().map(Call::from).toList();
            var r = step.response();
            return new Step(calls, r.inputTokens(), r.outputTokens(), r.latencyMs(), r.stopReason(), r.cost());
        }
    }

    public record Call(String tool, String arguments, String result, boolean error) {

        public static Call from(ToolResult result) {
            return new Call(result.call().name(), String.valueOf(result.call().arguments()),
                    result.content(), result.error());
        }
    }

    public int totalInputTokens() {
        return steps.stream().mapToInt(Step::inputTokens).sum();
    }

    public int totalOutputTokens() {
        return steps.stream().mapToInt(Step::outputTokens).sum();
    }

    public BigDecimal totalCost() {
        return steps.stream().map(s -> new BigDecimal(s.cost())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
