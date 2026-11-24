package ai.lab.broker.agent;

import ai.lab.broker.config.AgentProperties;
import ai.lab.broker.llm.ToolConversation;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The session history limit: whole exchanges are dropped from the start, the latest one stays. */
class SessionStoreTest {

    private static SessionStore store(int maxHistoryMessages) {
        return new SessionStore(new AgentProperties(6, 0.0, maxHistoryMessages));
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
    void sessionsAreKeyedByAccountAndSessionId() {
        SessionStore store = store(10);
        ToolConversation first = store.getOrCreate("BRK-1", "s", () -> new FakeConversation(1));

        assertSame(first, store.getOrCreate("BRK-1", "s", () -> new FakeConversation(1)));
        assertNotSame(first, store.getOrCreate("BRK-2", "s", () -> new FakeConversation(1)));
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
