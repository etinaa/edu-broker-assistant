package ai.lab.broker.broker;

import ai.lab.broker.domain.Order;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * In-memory orders by id, oldest first. Ids are generated in sequence: ORD-1, ORD-2, …
 * The book is bounded by {@link #evictOldest}: without it every finished order would stay in memory for good.
 */
@Component
public class OrderBook {

    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public String nextId() {
        return "ORD-" + sequence.incrementAndGet();
    }

    public synchronized List<Order> all() {
        return List.copyOf(orders.values());
    }

    public synchronized Optional<Order> byId(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    public synchronized void add(Order order) {
        orders.put(order.getId(), order);
    }

    /**
     * Removes the oldest orders that {@code removable} allows until at most {@code maxOrders} are left. Orders it
     * refuses (still waiting for confirmation) are kept, so the book may stay above the limit until they finish.
     *
     * @return the number of removed orders
     */
    public synchronized int evictOldest(int maxOrders, Predicate<Order> removable) {
        int removed = 0;
        Iterator<Order> oldestFirst = orders.values().iterator();
        while (orders.size() > maxOrders && oldestFirst.hasNext()) {
            if (removable.test(oldestFirst.next())) {
                oldestFirst.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Removes all orders and restarts ids from ORD-1; lets tests share one Spring context. */
    public synchronized void clear() {
        orders.clear();
        sequence.set(0);
    }
}
