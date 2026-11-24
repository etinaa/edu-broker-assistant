package ai.lab.broker.config;

import ai.lab.broker.llm.ClaudeClient;
import ai.lab.broker.llm.ClaudeToolConversation;
import ai.lab.broker.llm.OllamaClient;
import ai.lab.broker.llm.OllamaToolConversation;
import ai.lab.broker.llm.ToolConversationFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Task 2: the provider is chosen by configuration ({@code llm.provider}). The rest of the code sees only
 * {@link ToolConversationFactory} and does not know whether Claude or Ollama is behind it. Only the selected
 * provider's client is created, so the application starts on Ollama without ANTHROPIC_API_KEY.
 */
@Configuration
class LlmConfig {

    @Bean
    ToolConversationFactory toolConversationFactory(LlmProperties llm, AgentProperties agent) {
        return switch (llm.provider()) {
            case CLAUDE -> {
                LlmProperties.Claude c = llm.claude();
                if (c.apiKey() == null || c.apiKey().isBlank()) {
                    throw new IllegalStateException("llm.provider=claude, but ANTHROPIC_API_KEY is not set");
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
