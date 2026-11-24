package ai.lab.broker.config;

import ai.lab.broker.llm.ClaudeClient;
import ai.lab.broker.llm.ClaudeToolConversation;
import ai.lab.broker.llm.OllamaClient;
import ai.lab.broker.llm.OllamaToolConversation;
import ai.lab.broker.llm.ToolConversationFactory;

import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Task 2: the provider is chosen by configuration ({@code llm.provider}, or the LLM_PROVIDER variable). The rest
 * of the code sees only {@link ToolConversationFactory} and does not know whether Claude or Ollama is behind it.
 * Only the selected provider's client is created, so the application starts on Ollama without ANTHROPIC_API_KEY.
 * The chosen provider and model are logged at startup, so a run shows which model it used.
 */
@Slf4j
@Configuration
class LlmConfig {

    @Bean
    ToolConversationFactory toolConversationFactory(LlmProperties llm, AgentProperties agent) {
        if (llm.provider() == null) {
            throw new IllegalStateException("llm.provider is not set: use ollama or claude");
        }
        return switch (llm.provider()) {
            case CLAUDE -> claude(llm.claude(), agent);
            case OLLAMA -> ollama(llm.ollama(), agent);
        };
    }

    private static ToolConversationFactory claude(LlmProperties.Claude settings, AgentProperties agent) {
        if (settings.apiKey() == null || settings.apiKey().isBlank()) {
            throw new IllegalStateException("llm.provider=claude, but ANTHROPIC_API_KEY is not set");
        }
        ClaudeClient client = new ClaudeClient(settings.model(), settings.apiKey(), settings.maxTokens(),
                settings.inputPricePerMillion(), settings.outputPricePerMillion());
        log.info("LLM provider: {}", client.name());
        return (system, tools) -> new ClaudeToolConversation(client, system, tools, null, agent.temperature());
    }

    private static ToolConversationFactory ollama(LlmProperties.Ollama settings, AgentProperties agent) {
        OllamaClient client = new OllamaClient(settings.baseUrl(), settings.model(), settings.numCtx());
        log.info("LLM provider: {} at {}", client.name(), settings.baseUrl());
        return (system, tools) -> new OllamaToolConversation(client, system, tools, agent.temperature());
    }
}
