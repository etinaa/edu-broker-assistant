package ai.lab.broker.agent;

import ai.lab.broker.config.AgentProperties;
import ai.lab.broker.llm.ToolConversation;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sessions in memory: (account, sessionId) → conversation with the model (the whole history). The raw API has no
 * memory, so it lives here. The key includes the account: the history holds the balance and positions, and a client
 * of another account with the same sessionId must not get someone else's conversation.
 * <p>
 * Task 4, history limit: a sliding window. When the history is longer than {@code agent.max-history-messages},
 * the oldest exchanges are dropped whole (a user message with its tool calls and results), so the request keeps
 * fitting the context window. The latest exchange is never dropped. Older context is lost, but nothing that matters
 * for money: orders live in the order book, and numbers come from the tools again.
 * <p>
 * The number of sessions is bounded by {@code agent.max-sessions}: the least recently used session is evicted when
 * a new one does not fit. sessionId is chosen by the client, so without the bound every new id would stay in memory
 * forever. An evicted session simply starts over with an empty history.
 */
@Component
public class SessionStore {

    private record Key(String accountId, String sessionId) {}

    /** Access order: get() moves a session to the end, so the eldest entry is the least recently used. */
    private final Map<Key, ToolConversation> sessions;
    private final int maxHistoryMessages;

    public SessionStore(AgentProperties agent) {
        if (agent.maxHistoryMessages() <= 0) {
            throw new IllegalArgumentException("agent.max-history-messages must be positive");
        }
        if (agent.maxSessions() <= 0) {
            throw new IllegalArgumentException("agent.max-sessions must be positive");
        }
        this.maxHistoryMessages = agent.maxHistoryMessages();
        int maxSessions = agent.maxSessions();
        this.sessions = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, ToolConversation> eldest) {
                return size() > maxSessions;
            }
        };
    }

    /** The factory only builds an object (no network call), so holding the store's lock while it runs is cheap. */
    public synchronized ToolConversation getOrCreate(String accountId, String sessionId,
                                                     Supplier<ToolConversation> factory) {
        return sessions.computeIfAbsent(new Key(accountId, sessionId), key -> factory.get());
    }

    public synchronized int size() {
        return sessions.size();
    }

    /**
     * Drops the oldest exchanges until the history fits the limit or only the latest exchange is left.
     * Call it under the conversation's lock, before adding a new message.
     *
     * @return the number of dropped exchanges
     */
    public int fitHistory(ToolConversation conversation) {
        int dropped = 0;
        while (conversation.size() > maxHistoryMessages && conversation.dropOldestExchange()) {
            dropped++;
        }
        return dropped;
    }

    public synchronized void remove(String accountId, String sessionId) {
        sessions.remove(new Key(accountId, sessionId));
    }

    public synchronized void clear() {
        sessions.clear();
    }
}
