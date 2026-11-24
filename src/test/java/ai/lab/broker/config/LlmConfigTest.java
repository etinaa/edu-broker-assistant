package ai.lab.broker.config;

import ai.lab.broker.llm.ClaudeToolConversation;
import ai.lab.broker.llm.OllamaToolConversation;
import ai.lab.broker.llm.ToolConversation;
import ai.lab.broker.llm.ToolConversationFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 2: switching the provider is a configuration change only. No request is sent to a model here:
 * clients are created, but nothing calls them.
 */
class LlmConfigTest {

    @Configuration
    @EnableConfigurationProperties({LlmProperties.class, AgentProperties.class})
    static class Properties {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Properties.class, LlmConfig.class)
            .withPropertyValues(
                    "agent.max-steps=6", "agent.temperature=0.0",
                    "llm.claude.model=claude-test", "llm.claude.max-tokens=1024",
                    "llm.claude.input-price-per-million=1.00", "llm.claude.output-price-per-million=5.00",
                    "llm.ollama.base-url=http://localhost:11434", "llm.ollama.model=qwen-test",
                    "llm.ollama.num-ctx=16384");

    private static ToolConversation conversation(ToolConversationFactory factory) {
        return factory.create("system", new ObjectMapper().createArrayNode());
    }

    @Test
    void ollamaProviderCreatesOllamaConversation() {
        runner.withPropertyValues("llm.provider=ollama").run(context -> {
            ToolConversation conversation = conversation(context.getBean(ToolConversationFactory.class));
            assertInstanceOf(OllamaToolConversation.class, conversation);
            assertEquals("ollama/qwen-test", conversation.name());
        });
    }

    @Test
    void claudeProviderCreatesClaudeConversation() {
        runner.withPropertyValues("llm.provider=claude", "llm.claude.api-key=test-key").run(context -> {
            ToolConversation conversation = conversation(context.getBean(ToolConversationFactory.class));
            assertInstanceOf(ClaudeToolConversation.class, conversation);
            assertEquals("claude/claude-test", conversation.name());
        });
    }

    @Test
    void providerNameIsCaseInsensitive() {
        runner.withPropertyValues("llm.provider=OLLAMA").run(context ->
                assertInstanceOf(OllamaToolConversation.class,
                        conversation(context.getBean(ToolConversationFactory.class))));
    }

    @Test
    void claudeWithoutApiKeyFailsAtStartupWithClearMessage() {
        runner.withPropertyValues("llm.provider=claude", "llm.claude.api-key=").run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootCause(context.getStartupFailure()).getMessage().contains("ANTHROPIC_API_KEY"));
        });
    }

    @Test
    void ollamaDoesNotNeedApiKey() {
        runner.withPropertyValues("llm.provider=ollama", "llm.claude.api-key=").run(context ->
                assertNull(context.getStartupFailure()));
    }

    @Test
    void missingProviderFailsAtStartup() {
        runner.run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootCause(context.getStartupFailure()).getMessage().contains("llm.provider"));
        });
    }

    private static Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
