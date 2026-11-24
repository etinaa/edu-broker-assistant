package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Creates an empty conversation for the selected provider. tools — definitions in Claude format
 * (name, description, input_schema); for Ollama they are converted inside {@link OllamaToolConversation}.
 * Tests (task 8) substitute a scripted fake here.
 */
@FunctionalInterface
public interface ToolConversationFactory {

    ToolConversation create(String system, ArrayNode tools);
}
