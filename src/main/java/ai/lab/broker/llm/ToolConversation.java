package ai.lab.broker.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Диалог с инструментами независимо от провайдера (перенесено из урока 1.4). Реализация хранит историю в
 * формате своего API (блоки tool_use/tool_result у Claude, tool_calls и роль tool у Ollama), а цикл видит
 * только запросы на вызов и результаты.
 * <p>
 * Отличие от урока 1.4: диалог живёт дольше одного вопроса — это сессия пользователя. Поэтому первый вопрос
 * не передаётся в конструктор, а каждое новое сообщение добавляется через {@link #addUserMessage(String)}.
 */
public interface ToolConversation {

    /** Запрос модели на вызов инструмента; id — null, если провайдер его не прислал. */
    record ToolCall(String id, String name, JsonNode arguments) {
        @Override
        public String toString() {
            return name + arguments;
        }
    }

    /** error — результат описывает ошибку (у Claude уходит как is_error: true, у Ollama — пометкой в тексте). */
    record ToolResult(ToolCall call, String content, boolean error) {
        public static ToolResult ok(ToolCall call, String content) {
            return new ToolResult(call, content, false);
        }

        public static ToolResult error(ToolCall call, String message) {
            return new ToolResult(call, message, true);
        }
    }

    /** Ответ модели на одном шаге: пустой calls — модель ответила текстом, цикл закончен. */
    record Turn(List<ToolCall> calls, LlmResponse response) {}

    /** Новое сообщение пользователя в историю сессии. */
    void addUserMessage(String text);

    /** Отправить историю модели; ответ модели добавляется в историю целиком. */
    Turn send() throws Exception;

    /** Результаты всех вызовов одного шага — одним сообщением или по сообщению на вызов, как требует провайдер. */
    void addToolResults(List<ToolResult> results);

    /** Сколько сообщений в истории — пригодится для лимита истории сессии. */
    int size();

    String name();
}
