package ai.lab.broker.llm;

/** Нормализованный ответ модели: текст + то, за что мы платим и чего ждём. cost — USD строкой (у Ollama "0"). */
public record LlmResponse(String text, int inputTokens, int outputTokens, long latencyMs,
                          String stopReason, String cost) {

    @Override
    public String toString() {
        return "in=%d tok, out=%d tok, %d ms, stop-reason=%s cost=$%s %n---%n%s"
                .formatted(inputTokens, outputTokens, latencyMs, stopReason, cost, text);
    }
}
