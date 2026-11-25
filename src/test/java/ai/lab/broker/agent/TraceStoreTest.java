package ai.lab.broker.agent;

import ai.lab.broker.config.AgentProperties;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Traces are bounded: the oldest one is evicted first. */
class TraceStoreTest {

    private static TraceStore store(int maxTraces) {
        return new TraceStore(new AgentProperties(6, 0.0, 40, 100, maxTraces));
    }

    private static Trace trace(TraceStore store) {
        Trace trace = new Trace(store.nextId(), "BRK-1", "s1", "hi", "test", List.of(), "ok", false, Instant.now());
        store.save(trace);
        return trace;
    }

    @Test
    void savedTraceIsFoundById() {
        TraceStore store = store(10);
        Trace saved = trace(store);

        assertEquals("TR-1", saved.id());
        assertSame(saved, store.find("TR-1").orElseThrow());
    }

    @Test
    void oldestTraceIsEvictedWhenFull() {
        TraceStore store = store(2);
        trace(store);
        trace(store);
        trace(store);

        assertEquals(2, store.size());
        assertTrue(store.find("TR-1").isEmpty());
        assertTrue(store.find("TR-2").isPresent());
        assertTrue(store.find("TR-3").isPresent());
    }

    @Test
    void maxTracesMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> store(0));
    }
}
