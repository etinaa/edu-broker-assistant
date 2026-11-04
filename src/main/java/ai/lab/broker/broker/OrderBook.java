package ai.lab.broker.broker;

import ai.lab.broker.domain.Order;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Заявки в памяти. Идентификаторы вида ORD-1, ORD-2… */
@Component
public class OrderBook {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public String nextId() {
        return "ORD-" + sequence.incrementAndGet();
    }

    public void save(Order order) {
        orders.put(order.id(), order);
    }

    public Optional<Order> find(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    public List<Order> all() {
        return List.copyOf(orders.values());
    }

    public void reset() {
        orders.clear();
        sequence.set(0);
    }
}
