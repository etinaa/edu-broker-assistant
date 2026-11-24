package ai.lab.broker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/** Model settings from the {@code llm} section of {@code application.yml}. */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(Provider provider, Claude claude, Ollama ollama) {

    public enum Provider { CLAUDE, OLLAMA }

    public record Claude(String model, String apiKey, int maxTokens,
                         BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion) {}

    public record Ollama(String baseUrl, String model, Integer numCtx) {}
}
