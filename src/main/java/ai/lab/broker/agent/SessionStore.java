package ai.lab.broker.agent;

import ai.lab.broker.config.AgentProperties;
import ai.lab.broker.llm.ToolConversation;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
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
 * <p>
 * Eviction must not let one client hurt another: an account keeps at most {@code agent.max-sessions-per-account},
 * and a new session beyond it evicts that account's own least recently used session, not someone else's. A session
 * whose lock is held (a message is being answered) is never evicted, so an answer is not written into a forgotten
 * history; while every candidate is busy the store may stay above its limit.
 * <p>
 * Each session has its own lock: one message of a session at a time. {@link AssistantService} holds it for the
 * whole answer (model calls included) and takes it with a timeout, so a second message is refused instead of
 * waiting minutes. A session removed or evicted while someone waited for its lock is no longer
 * {@link #isCurrent current}: the waiter takes the new session instead of writing into a forgotten one.
 */
@Component
public class SessionStore {

    private record Key(String accountId, String sessionId) {}

    /** A conversation and the lock that lets one message of the session at a time use it. */
    public record Session(ToolConversation conversation, ReentrantLock lock) {
        Session(ToolConversation conversation) {
            this(conversation, new ReentrantLock());
        }
    }

    /** Access order: get() moves a session to the end, so the eldest entry is the least recently used. */
    private final Map<Key, Session> sessions;
    private final int maxHistoryMessages;
    private final int maxSessions;
    private final int maxSessionsPerAccount;

    public SessionStore(AgentProperties agent) {
        if (agent.maxHistoryMessages() <= 0) {
            throw new IllegalArgumentException("agent.max-history-messages must be positive");
        }
        if (agent.maxSessions() <= 0) {
            throw new IllegalArgumentException("agent.max-sessions must be positive");
        }
        if (agent.maxSessionsPerAccount() <= 0) {
            throw new IllegalArgumentException("agent.max-sessions-per-account must be positive");
        }
        this.maxHistoryMessages = agent.maxHistoryMessages();
        this.maxSessions = agent.maxSessions();
        this.maxSessionsPerAccount = agent.maxSessionsPerAccount();
        this.sessions = new LinkedHashMap<>(16, 0.75f, true);
    }

    /** The factory only builds an object (no network call), so holding the store's lock while it runs is cheap. */
    public synchronized Session getOrCreate(String accountId, String sessionId, Supplier<ToolConversation> factory) {
        Key key = new Key(accountId, sessionId);
        Session existing = sessions.get(key);
        if (existing != null) {
            return existing;
        }
        Session created = new Session(factory.get());
        sessions.put(key, created);
        evictLeastRecentlyUsed(key, accountId);
        return created;
    }

    /**
     * First the account's own sessions above its limit, then any sessions above the global limit; oldest use first,
     * skipping the new session and busy ones. O(sessions) per new session, which is fine for a bounded store.
     */
    private void evictLeastRecentlyUsed(Key created, String accountId) {
        long ofAccount = sessions.keySet().stream().filter(k -> k.accountId().equals(accountId)).count();
        Iterator<Map.Entry<Key, Session>> eldestFirst = sessions.entrySet().iterator();
        while ((ofAccount > maxSessionsPerAccount || sessions.size() > maxSessions) && eldestFirst.hasNext()) {
            Map.Entry<Key, Session> entry = eldestFirst.next();
            boolean sameAccount = entry.getKey().accountId().equals(accountId);
            // Above only the account's limit, only that account's sessions may go.
            boolean evictable = sessions.size() > maxSessions || sameAccount;
            if (evictable && !entry.getKey().equals(created) && !entry.getValue().lock().isLocked()) {
                eldestFirst.remove();
                if (sameAccount) {
                    ofAccount--;
                }
            }
        }
    }

    public synchronized Optional<Session> find(String accountId, String sessionId) {
        return Optional.ofNullable(sessions.get(new Key(accountId, sessionId)));
    }

    /** False if the session was removed or evicted (and maybe replaced by a new one) since it was taken. */
    public synchronized boolean isCurrent(String accountId, String sessionId, Session session) {
        return sessions.get(new Key(accountId, sessionId)) == session;
    }

    public synchronized int size() {
        return sessions.size();
    }

    /**
     * Drops the oldest exchanges until the history fits the limit or only the latest exchange is left.
     * Call it under the session's lock, before adding a new message.
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

    /** Removes the session only if it is still this one, so a reset never drops a newer session by mistake. */
    public synchronized void remove(String accountId, String sessionId, Session session) {
        sessions.remove(new Key(accountId, sessionId), session);
    }

    public synchronized void clear() {
        sessions.clear();
    }
}
