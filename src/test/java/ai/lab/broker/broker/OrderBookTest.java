package ai.lab.broker.broker;

import ai.lab.broker.domain.Order;
import ai.lab.broker.domain.OrderSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookTest {

    private OrderBook book;

    @BeforeEach
    void setUp() {
        book = new OrderBook();
    }

    private static Order order(String id) {
        return new Order(id, "BRK-1", OrderSide.BUY, "TRNC", 1, new BigDecimal("185.25"), Instant.now());
    }

    @Test
    void evictOldestRemovesOnlyAllowedOrdersOldestFirst() {
        Order kept = order("ORD-1");
        Order oldFinished = order("ORD-2");
        Order newFinished = order("ORD-3");
        Order newest = order("ORD-4");
        List.of(kept, oldFinished, newFinished, newest).forEach(book::add);

        int removed = book.evictOldest(2, o -> !o.getId().equals("ORD-1"));

        assertEquals(2, removed);
        assertEquals(List.of(kept, newest), book.all());
    }

    @Test
    void evictOldestKeepsEverythingWhenNothingMayGo() {
        List.of(order("ORD-1"), order("ORD-2"), order("ORD-3")).forEach(book::add);

        assertEquals(0, book.evictOldest(1, o -> false));
        assertEquals(3, book.all().size());
        assertEquals(0, book.evictOldest(5, o -> true));
    }

    @Test
    void nextIdStartsAtOneAndIncrements() {
        assertEquals("ORD-1", book.nextId());
        assertEquals("ORD-2", book.nextId());
        assertEquals("ORD-3", book.nextId());
    }

    @Test
    void nextIdIsUniqueAcrossThreads() throws Exception {
        int threads = 8, perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<String>>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    List<String> ids = new java.util.ArrayList<>();
                    for (int i = 0; i < perThread; i++) ids.add(book.nextId());
                    return ids;
                }));
            }
            Set<String> all = new HashSet<>();
            for (Future<List<String>> f : futures) all.addAll(f.get(10, TimeUnit.SECONDS));
            assertEquals(threads * perThread, all.size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void emptyBookHasNoOrders() {
        assertTrue(book.all().isEmpty());
        assertTrue(book.byId("ORD-1").isEmpty());
    }

    @Test
    void addedOrderIsFoundById() {
        Order o = order("ORD-1");
        book.add(o);

        assertSame(o, book.byId("ORD-1").orElseThrow());
    }

    @Test
    void byIdReturnsEmptyForUnknownId() {
        book.add(order("ORD-1"));

        assertTrue(book.byId("ORD-2").isEmpty());
    }

    @Test
    void byIdIsCaseSensitive() {
        book.add(order("ORD-1"));

        assertTrue(book.byId("ord-1").isEmpty());
    }

    @Test
    void allReturnsEveryAddedOrder() {
        Order a = order("ORD-1");
        Order b = order("ORD-2");
        book.add(a);
        book.add(b);

        assertEquals(2, book.all().size());
        assertTrue(book.all().containsAll(List.of(a, b)));
    }

    @Test
    void addingOrderWithSameIdReplacesPreviousOne() {
        Order first = order("ORD-1");
        Order second = order("ORD-1");
        book.add(first);
        book.add(second);

        assertEquals(1, book.all().size());
        assertSame(second, book.byId("ORD-1").orElseThrow());
    }

    @Test
    void allReturnsUnmodifiableSnapshot() {
        book.add(order("ORD-1"));
        List<Order> snapshot = book.all();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(order("ORD-2")));

        book.add(order("ORD-2"));
        assertEquals(1, snapshot.size());
        assertEquals(2, book.all().size());
    }

    @Test
    void nextIdDoesNotAddAnOrder() {
        book.nextId();

        assertTrue(book.all().isEmpty());
    }
}
