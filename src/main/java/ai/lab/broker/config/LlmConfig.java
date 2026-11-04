package ai.lab.broker.config;

import ai.lab.broker.llm.ClaudeClient;
import ai.lab.broker.llm.ClaudeToolConversation;
import ai.lab.broker.llm.OllamaClient;
import ai.lab.broker.llm.OllamaToolConversation;
import ai.lab.broker.llm.ToolConversationFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Задание 2: провайдер выбирается конфигурацией. Остальной код видит только {@link ToolConversationFactory}
 * и не знает, Claude перед ним или Ollama. Создаётся только клиент выбранного провайдера, поэтому без
 * ANTHROPIC_API_KEY приложение спокойно стартует на Ollama.
 */
@Configuration
class LlmConfig {

    @Bean
    ToolConversationFactory toolConversationFactory(LlmProperties llm, AgentProperties agent) {
        return switch (llm.provider()) {
            case CLAUDE -> {
                LlmProperties.Claude c = llm.claude();
                if (c.apiKey() == null || c.apiKey().isBlank()) {
                    throw new IllegalStateException("llm.provider=claude, но ANTHROPIC_API_KEY не задан");
                }
                ClaudeClient client = new ClaudeClient(c.model(), c.apiKey(), c.maxTokens(),
                        c.inputPricePerMillion(), c.outputPricePerMillion());
                yield (system, tools) -> new ClaudeToolConversation(client, system, tools, null, agent.temperature());
            }
            case OLLAMA -> {
                LlmProperties.Ollama o = llm.ollama();
                OllamaClient client = new OllamaClient(o.baseUrl(), o.model(), o.numCtx());
                yield (system, tools) -> new OllamaToolConversation(client, system, tools, agent.temperature());
            }
        };
    }
}
