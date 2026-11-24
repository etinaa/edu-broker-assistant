package ai.lab.broker.llm;

/** Normalized model response: the text plus what we pay for and wait for. cost — USD as a string ("0" for Ollama). */
public record LlmResponse(String text, int inputTokens, int outputTokens, long latencyMs,
                          String stopReason, String cost) {

    @Override
    public String toString() {
        return "in=%d tok, out=%d tok, %d ms, stop-reason=%s cost=$%s %n---%n%s"
                .formatted(inputTokens, outputTokens, latencyMs, stopReason, cost, text);
    }
}
