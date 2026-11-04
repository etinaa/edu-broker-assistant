package ai.lab.broker.agent;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Трассы в памяти. Идентификаторы вида TR-1, TR-2… */
@Component
public class TraceStore {

    private final Map<String, Trace> traces = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public String nextId() {
        return "TR-" + sequence.incrementAndGet();
    }

    public void save(Trace trace) {
        traces.put(trace.id(), trace);
    }

    public Optional<Trace> find(String id) {
        return Optional.ofNullable(traces.get(id));
    }

    public void clear() {
        traces.clear();
        sequence.set(0);
    }
}
