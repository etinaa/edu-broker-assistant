package ai.lab.broker.agent;

import ai.lab.broker.llm.ToolConversation;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Сессии в памяти: (счёт, sessionId) → диалог с моделью (вся история). У raw API памяти нет — она здесь.
 * Ключ включает счёт: в истории лежат баланс и позиции, и клиент другого счёта с тем же sessionId
 * не должен получить чужой диалог.
 * Задание 4: лимит длины истории (что делать, когда сессия перестаёт помещаться в окно контекста?).
 */
@Component
public class SessionStore {

    private record Key(String accountId, String sessionId) {}

    private final Map<Key, ToolConversation> sessions = new ConcurrentHashMap<>();

    public ToolConversation getOrCreate(String accountId, String sessionId, Supplier<ToolConversation> factory) {
        return sessions.computeIfAbsent(new Key(accountId, sessionId), key -> factory.get());
    }

    public void remove(String accountId, String sessionId) {
        sessions.remove(new Key(accountId, sessionId));
    }

    public void clear() {
        sessions.clear();
    }
}
