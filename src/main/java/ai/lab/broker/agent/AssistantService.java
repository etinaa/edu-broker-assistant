package ai.lab.broker.agent;

import ai.lab.broker.config.AgentProperties;
import ai.lab.broker.llm.ToolConversationFactory;
import ai.lab.broker.web.dto.ChatResponse;

import org.springframework.stereotype.Service;

/**
 * Обработка одного сообщения пользователя: сессия → агентный цикл → структурированный ответ → трасса.
 */
@Service
public class AssistantService {

    private final ToolConversationFactory conversations;
    private final BrokerTools tools;
    private final SessionStore sessions;
    private final TraceStore traces;
    private final AgentProperties agent;

    public AssistantService(ToolConversationFactory conversations, BrokerTools tools, SessionStore sessions,
                            TraceStore traces, AgentProperties agent) {
        this.conversations = conversations;
        this.tools = tools;
        this.sessions = sessions;
        this.traces = traces;
        this.agent = agent;
    }

    /**
     * Задание 4. accountId приходит из заголовка запроса и дальше идёт только через код: модель его не видит.
     * Примерный порядок:
     * <ol>
     *   <li>диалог сессии: {@code sessions.getOrCreate(accountId, sessionId, () -> conversations.create(SystemPrompt.TEXT,
     *       tools.definitions()))}; два параллельных запроса одной сессии не должны перемешать историю;</li>
     *   <li>{@code conversation.addUserMessage(message)};</li>
     *   <li>{@code ToolLoop.run(conversation, call -> tools.execute(accountId, call), agent.maxSteps())}
     *       (исполнитель создаётся на каждый запрос и замыкает accountId; для GroundedExecutor — тот же делегат); при limitReached — честный
     *       ответ «не уложился», а не обрывок текста модели;</li>
     *   <li>pendingOrder и candidates собрать из результатов инструментов этого сообщения (не из текста модели);</li>
     *   <li>{@link Trace} из шагов ({@link Trace.Step#from}) → {@code traces.save(...)};</li>
     *   <li>вернуть {@link ChatResponse}.</li>
     * </ol>
     * Исключения провайдера (сеть, 429, 5xx) — не стек пользователю, а понятная ошибка API.
     */
    public ChatResponse chat(String accountId, String sessionId, String message) {
        throw new UnsupportedOperationException("Task 4: AssistantService.chat");
    }

    /** Новая сессия с чистой историей. */
    public void resetSession(String accountId, String sessionId) {
        sessions.remove(accountId, sessionId);
    }
}
