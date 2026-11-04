package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Создаёт пустой диалог выбранного провайдера. tools — определения в формате Claude
 * (name, description, input_schema); для Ollama они переводятся внутри {@link OllamaToolConversation}.
 * В тестах (задание 8) сюда подставляется сценарный фейк.
 */
@FunctionalInterface
public interface ToolConversationFactory {

    ToolConversation create(String system, ArrayNode tools);
}
