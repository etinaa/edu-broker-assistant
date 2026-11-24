package ai.lab.broker.agent;

import ai.lab.broker.config.AgentProperties;
import ai.lab.broker.llm.ToolConversation;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 */
@Component
public class SessionStore {

    private record Key(String accountId, String sessionId) {}

    private final Map<Key, ToolConversation> sessions = new ConcurrentHashMap<>();
    private final int maxHistoryMessages;

    public SessionStore(AgentProperties agent) {
        if (agent.maxHistoryMessages() <= 0) {
            throw new IllegalArgumentException("agent.max-history-messages must be positive");
        }
        this.maxHistoryMessages = agent.maxHistoryMessages();
    }

    public ToolConversation getOrCreate(String accountId, String sessionId, Supplier<ToolConversation> factory) {
        return sessions.computeIfAbsent(new Key(accountId, sessionId), key -> factory.get());
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

    public void remove(String accountId, String sessionId) {
        sessions.remove(new Key(accountId, sessionId));
    }

    public void clear() {
        sessions.clear();
    }
}
