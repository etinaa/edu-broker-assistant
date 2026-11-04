package ai.lab.broker.agent;

import ai.lab.broker.llm.ToolConversation;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Сессии в памяти: sessionId → диалог с моделью (вся история). У raw API памяти нет — она здесь.
 * Задание 4: лимит длины истории (что делать, когда сессия перестаёт помещаться в окно контекста?).
 */
@Component
public class SessionStore {

    private final Map<String, ToolConversation> sessions = new ConcurrentHashMap<>();

    public ToolConversation getOrCreate(String sessionId, Supplier<ToolConversation> factory) {
        return sessions.computeIfAbsent(sessionId, id -> factory.get());
    }

    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    public void clear() {
        sessions.clear();
    }
}
