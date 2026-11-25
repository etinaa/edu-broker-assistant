package ai.lab.broker.agent;

import ai.lab.broker.config.AgentProperties;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Traces in memory, with ids TR-1, TR-2… At most {@code agent.max-traces} are kept: the oldest trace is evicted
 * when a new one does not fit, so traces cannot grow until the heap runs out.
 */
@Component
public class TraceStore {

    /** Insertion order: the eldest entry is the oldest trace. */
    private final Map<String, Trace> traces;
    private final AtomicLong sequence = new AtomicLong();

    public TraceStore(AgentProperties agent) {
        if (agent.maxTraces() <= 0) {
            throw new IllegalArgumentException("agent.max-traces must be positive");
        }
        int maxTraces = agent.maxTraces();
        this.traces = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Trace> eldest) {
                return size() > maxTraces;
            }
        };
    }

    public String nextId() {
        return "TR-" + sequence.incrementAndGet();
    }

    public synchronized void save(Trace trace) {
        traces.put(trace.id(), trace);
    }

    public synchronized Optional<Trace> find(String id) {
        return Optional.ofNullable(traces.get(id));
    }

    public synchronized int size() {
        return traces.size();
    }

    public synchronized void clear() {
        traces.clear();
        sequence.set(0);
    }
}
