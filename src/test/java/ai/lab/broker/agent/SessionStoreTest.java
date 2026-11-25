package ai.lab.broker.agent;

import ai.lab.broker.config.AgentProperties;
import ai.lab.broker.llm.ToolConversation;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The session history limit: whole exchanges are dropped from the start, the latest one stays. */
class SessionStoreTest {

    private static SessionStore store(int maxHistoryMessages) {
        return new SessionStore(new AgentProperties(6, 0.0, maxHistoryMessages, 100, 100, 100, Duration.ofSeconds(2)));
    }

    private static SessionStore storeWithMaxSessions(int maxSessions) {
        return new SessionStore(new AgentProperties(6, 0.0, 10, maxSessions, maxSessions, 100, Duration.ofSeconds(2)));
    }

    @Test
    void historyWithinLimitIsKept() {
        FakeConversation conversation = new FakeConversation(3, 3);

        assertEquals(0, store(6).fitHistory(conversation));
        assertEquals(6, conversation.size());
    }

    @Test
    void oldestExchangesAreDroppedUntilHistoryFits() {
        FakeConversation conversation = new FakeConversation(4, 4, 4);

        assertEquals(2, store(5).fitHistory(conversation));
        assertEquals(4, conversation.size());
    }

    @Test
    void latestExchangeStaysEvenIfLongerThanLimit() {
        FakeConversation conversation = new FakeConversation(2, 10);

        assertEquals(1, store(5).fitHistory(conversation));
        assertEquals(10, conversation.size());
    }

    @Test
    void limitMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> store(0));
    }

    @Test
    void leastRecentlyUsedSessionIsEvicted() {
        SessionStore store = storeWithMaxSessions(2);
        SessionStore.Session first = store.getOrCreate("BRK-1", "a", () -> new FakeConversation(1));
        store.getOrCreate("BRK-1", "b", () -> new FakeConversation(1));
        store.getOrCreate("BRK-1", "a", () -> new FakeConversation(1)); // "a" is used again, "b" is now eldest
        store.getOrCreate("BRK-1", "c", () -> new FakeConversation(1));

        assertEquals(2, store.size());
        assertSame(first, store.getOrCreate("BRK-1", "a", () -> new FakeConversation(1)));
        ToolConversation fresh = new FakeConversation(1);
        assertSame(fresh, store.getOrCreate("BRK-1", "b", () -> fresh).conversation());
    }

    private static SessionStore storeWithLimits(int maxSessions, int maxSessionsPerAccount) {
        return new SessionStore(new AgentProperties(6, 0.0, 10, maxSessions, maxSessionsPerAccount, 100,
                Duration.ofSeconds(2)));
    }

    @Test
    void accountAboveItsLimitEvictsItsOwnSessionNotAnotherAccounts() {
        SessionStore store = storeWithLimits(100, 2);
        SessionStore.Session victim = store.getOrCreate("BRK-1", "mine", () -> new FakeConversation(1));
        SessionStore.Session flooderFirst = store.getOrCreate("BRK-2", "x1", () -> new FakeConversation(1));
        for (int i = 2; i <= 50; i++) {
            store.getOrCreate("BRK-2", "x" + i, () -> new FakeConversation(1));
        }

        assertTrue(store.isCurrent("BRK-1", "mine", victim), "another account's session stays");
        assertFalse(store.isCurrent("BRK-2", "x1", flooderFirst));
        assertEquals(3, store.size());
    }

    @Test
    void busySessionIsNeverEvicted() {
        SessionStore store = storeWithLimits(2, 2);
        SessionStore.Session busy = store.getOrCreate("BRK-1", "busy", () -> new FakeConversation(1));
        SessionStore.Session idle = store.getOrCreate("BRK-1", "idle", () -> new FakeConversation(1));
        busy.lock().lock();
        try {
            store.getOrCreate("BRK-1", "new", () -> new FakeConversation(1));

            assertTrue(store.isCurrent("BRK-1", "busy", busy));
            assertFalse(store.isCurrent("BRK-1", "idle", idle));

            idle = store.getOrCreate("BRK-1", "idle", () -> new FakeConversation(1));
            store.getOrCreate("BRK-1", "newer", () -> new FakeConversation(1));
            store.getOrCreate("BRK-1", "newest", () -> new FakeConversation(1));
            assertTrue(store.isCurrent("BRK-1", "busy", busy), "still kept while all others went");
            assertEquals(2, store.size());
        } finally {
            busy.lock().unlock();
        }
    }

    @Test
    void maxSessionsPerAccountMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> storeWithLimits(10, 0));
    }

    @Test
    void maxSessionsMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> storeWithMaxSessions(0));
    }

    @Test
    void sessionsAreKeyedByAccountAndSessionId() {
        SessionStore store = store(10);
        SessionStore.Session first = store.getOrCreate("BRK-1", "s", () -> new FakeConversation(1));

        assertSame(first, store.getOrCreate("BRK-1", "s", () -> new FakeConversation(1)));
        assertNotSame(first, store.getOrCreate("BRK-2", "s", () -> new FakeConversation(1)));
    }

    @Test
    void removedSessionIsNoLongerCurrentAndANewerOneIsNotRemovedByMistake() {
        SessionStore store = store(10);
        SessionStore.Session old = store.getOrCreate("BRK-1", "s", () -> new FakeConversation(1));

        store.remove("BRK-1", "s", old);
        SessionStore.Session newer = store.getOrCreate("BRK-1", "s", () -> new FakeConversation(1));
        store.remove("BRK-1", "s", old);

        assertFalse(store.isCurrent("BRK-1", "s", old));
        assertTrue(store.isCurrent("BRK-1", "s", newer));
        assertSame(newer, store.find("BRK-1", "s").orElseThrow());
    }

    /** A history made of exchanges with the given numbers of messages. */
    private static final class FakeConversation implements ToolConversation {
        private final Deque<Integer> exchanges = new ArrayDeque<>();

        FakeConversation(int... exchangeSizes) {
            for (int size : exchangeSizes) {
                exchanges.add(size);
            }
        }

        @Override
        public Seen seen() {
            return new Seen(List.of(), List.of());
        }

        @Override
        public int size() {
            return exchanges.stream().mapToInt(Integer::intValue).sum();
        }

        @Override
        public void truncate(int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean dropOldestExchange() {
            if (exchanges.size() <= 1) {
                return false;
            }
            exchanges.removeFirst();
            return true;
        }

        @Override
        public void addUserMessage(String text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Turn send() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addToolResults(List<ToolResult> results) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String name() {
            return "fake";
        }
    }
}
