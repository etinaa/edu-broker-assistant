package ai.lab.broker.broker;

import ai.lab.broker.domain.Order;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class OrderBook {

    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public String nextId() {
        return "ORD-" + sequence.incrementAndGet();
    }

    public List<Order> all() {
        return List.copyOf(orders.values());
    }

    public Optional<Order> byId(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    public void add(Order order) {
        orders.put(order.getId(), order);
    }
}
