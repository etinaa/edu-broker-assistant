package ai.lab.broker.broker;

import ai.lab.broker.config.BrokerProperties;
import ai.lab.broker.domain.*;
import ai.lab.broker.domain.OrderDecision.Reason;
import ai.lab.broker.domain.SearchResult.Match;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses the real in-memory collaborators with their demo data:
 * BRK-1: cash 10000.00, TRNC=20, SMPB=100, DEMO=5; BRK-2: cash 100.00, SMPL=10.
 * Prices: TRNC 185.25, SMPB 41.75, SMPL 12.30, DEMO 410.00.
 */
class BrokerServiceTest {

    private OrderBook orders;
    private SecurityCatalog catalog;
    private MutableClock clock;
    private BrokerService service;

    @BeforeEach
    void setUp() {
        orders = new OrderBook();
        catalog = new SecurityCatalog();
        clock = new MutableClock();
        service = TestBroker.with(catalog, orders, clock);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    /** BrokerService hands out snapshots: read the order again to see what happened to it since. */
    private Order current(Order order) {
        return service.orderById(order.getId()).orElseThrow();
    }

    private Order placeOk(String accountId, OrderSide side, String ticker, long qty) {
        OrderDecision d = service.placeOrder(accountId, side, ticker, qty);
        return assertInstanceOf(OrderDecision.Created.class, d).order();
    }

    private static Reason refusedReason(OrderDecision d) {
        return assertInstanceOf(OrderDecision.Refused.class, d).reason();
    }

    private static String refusedMessage(OrderDecision d) {
        return assertInstanceOf(OrderDecision.Refused.class, d).message();
    }

    // ---- concurrency ----

    /** Runs all tasks at the same moment on separate threads and returns their results. */
    private static List<OrderDecision> runConcurrently(List<java.util.function.Supplier<OrderDecision>> tasks)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<OrderDecision>> futures = new ArrayList<>();
            for (var task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.get();
                }));
            }
            start.countDown();
            List<OrderDecision> results = new ArrayList<>();
            for (Future<OrderDecision> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelConfirmsOfSameOrderExecuteItOnce() throws Exception {
        for (int round = 0; round < 50; round++) {
            setUp();
            Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 10);

            List<OrderDecision> results = runConcurrently(java.util.Collections.nCopies(16,
                    () -> service.confirmOrder("BRK-1", order.getId())));

            assertEquals(1, results.stream().filter(OrderDecision.Confirmed.class::isInstance).count());
            Account account = service.accountSnapshot("BRK-1").orElseThrow();
            assertMoney("8147.50", account.getCash());
            assertEquals(30L, account.quantityByTicker("TRNC"));
        }
    }

    @Test
    void parallelConfirmsOfTwoOrdersCannotOverspendCash() throws Exception {
        for (int round = 0; round < 50; round++) {
            setUp();
            // BRK-2 cash 100.00: two orders of 98.40 each pass at placement, only one can be executed.
            Order first = placeOk("BRK-2", OrderSide.BUY, "SMPL", 8);
            Order second = placeOk("BRK-2", OrderSide.BUY, "SMPL", 8);

            List<OrderDecision> results = runConcurrently(List.of(
                    () -> service.confirmOrder("BRK-2", first.getId()),
                    () -> service.confirmOrder("BRK-2", second.getId())));

            assertEquals(1, results.stream().filter(OrderDecision.Confirmed.class::isInstance).count());
            Account account = service.accountSnapshot("BRK-2").orElseThrow();
            assertMoney("1.60", account.getCash());
            assertEquals(18L, account.quantityByTicker("SMPL"));
        }
    }

    // ---- lookups ----

    @Test
    void currentPriceByTickerReturnsPriceOrEmpty() {
        assertMoney("185.25", service.currentPriceByTicker("TRNC").orElseThrow());
        assertMoney("185.25", service.currentPriceByTicker("trnc").orElseThrow());
        assertTrue(service.currentPriceByTicker("NOPE").isEmpty());
    }

    @Test
    void accountAndOrderLookups() {
        assertTrue(service.accountExists("BRK-1"));
        assertFalse(service.accountExists("nope"));
        assertTrue(service.orderById("ORD-1").isEmpty());

        Order o = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);
        assertEquals(o.getId(), service.orderById(o.getId()).orElseThrow().getId());
    }

    @Test
    void ordersAreHandedOutAsSnapshotsThatLaterChangesDoNotTouch() {
        Order placed = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);
        Order read = service.orderById(placed.getId()).orElseThrow();

        service.confirmOrder("BRK-1", placed.getId());

        assertNotSame(orders.byId(placed.getId()).orElseThrow(), read);
        assertEquals(OrderStatus.PENDING_CONFIRMATION, read.getStatus());
        assertEquals(OrderStatus.PENDING_CONFIRMATION, placed.getStatus());
        assertEquals(OrderStatus.EXECUTED, current(placed).getStatus());
    }

    @Test
    void finishedAndExpiredOrdersAreEvictedAboveTheLimitButPendingOnesStay() {
        BrokerProperties small = new BrokerProperties(TestBroker.PROPERTIES.orderTtl(),
                TestBroker.PROPERTIES.maxPriceMovePercent(), 2, BigDecimal.ZERO, BigDecimal.ZERO);
        BrokerService bounded = TestBroker.with(catalog, orders, clock, small);

        Order rejected = assertInstanceOf(OrderDecision.Created.class,
                bounded.placeOrder("BRK-1", OrderSide.BUY, "DEMO", 1)).order();
        bounded.rejectOrder("BRK-1", rejected.getId());
        Order pending = assertInstanceOf(OrderDecision.Created.class,
                bounded.placeOrder("BRK-1", OrderSide.BUY, "DEMO", 1)).order();
        Order third = assertInstanceOf(OrderDecision.Created.class,
                bounded.placeOrder("BRK-1", OrderSide.BUY, "DEMO", 1)).order();

        assertTrue(bounded.orderById(rejected.getId()).isEmpty(), "finished order is evicted");
        assertTrue(bounded.orderById(pending.getId()).isPresent());
        assertTrue(bounded.orderById(third.getId()).isPresent());

        bounded.placeOrder("BRK-1", OrderSide.BUY, "DEMO", 1);
        assertEquals(3, orders.all().size(), "pending orders are kept above the limit");

        clock.advance(small.orderTtl().plusSeconds(1));
        Order fresh = assertInstanceOf(OrderDecision.Created.class,
                bounded.placeOrder("BRK-1", OrderSide.BUY, "DEMO", 1)).order();
        assertEquals(2, orders.all().size(), "expired orders are evicted");
        assertTrue(bounded.orderById(fresh.getId()).isPresent());
    }

    @Test
    void accountSnapshotIsACopyThatLaterOperationsDoNotChange() {
        Account snapshot = service.accountSnapshot("BRK-1").orElseThrow();
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 2);
        service.confirmOrder("BRK-1", order.getId());

        assertMoney("10000.00", snapshot.getCash());
        assertEquals(20L, snapshot.quantityByTicker("TRNC"));
        Account after = service.accountSnapshot("BRK-1").orElseThrow();
        assertMoney("9629.50", after.getCash());
        assertEquals(22L, after.quantityByTicker("TRNC"));
        assertNotSame(service.accountSnapshot("BRK-1").orElseThrow(), after);
        assertTrue(service.accountSnapshot("nope").isEmpty());
    }

    // ---- task 6: confirmation re-checks ----

    @Test
    void twoOrdersOf15DemoBothPassAtPlacementButOnlyTheFirstCanBeConfirmed() {
        // 15 * 410.00 = 6150.00 each; 10000.00 covers one of them, not both.
        Order first = placeOk("BRK-1", OrderSide.BUY, "DEMO", 15);
        Order second = placeOk("BRK-1", OrderSide.BUY, "DEMO", 15);

        assertInstanceOf(OrderDecision.Confirmed.class, service.confirmOrder("BRK-1", first.getId()));
        OrderDecision d = service.confirmOrder("BRK-1", second.getId());

        assertEquals(Reason.INSUFFICIENT_FUNDS, refusedReason(d));
        assertMoney("2300.00", ((OrderDecision.Refused) d).shortfall());
        assertMoney("3850.00", service.accountSnapshot("BRK-1").orElseThrow().getCash());
        assertEquals(OrderStatus.FAILED, current(second).getStatus());
    }

    @Test
    void buyExecutesAtCurrentPriceWhenItRoseExactlyByTheAllowedPercent() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "DEMO", 10);
        catalog.updatePrice("DEMO", new BigDecimal("418.20")); // +2.00%

        assertInstanceOf(OrderDecision.Confirmed.class, service.confirmOrder("BRK-1", order.getId()));

        assertMoney("5818.00", service.accountSnapshot("BRK-1").orElseThrow().getCash()); // 10000 - 10 * 418.20
        assertEquals("Order confirmed at 418.20 USD", current(order).getReason());
    }

    @Test
    void buyFailsWhenPriceRoseByMoreThanAllowedAndAccountIsUntouched() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "DEMO", 10);
        catalog.updatePrice("DEMO", new BigDecimal("418.21"));

        OrderDecision d = service.confirmOrder("BRK-1", order.getId());

        assertEquals(Reason.PRICE_CHANGED, refusedReason(d));
        assertEquals("Price of DEMO moved from 410.00 to 418.21 USD, more than the allowed 2%; place a new order",
                refusedMessage(d));
        assertEquals(OrderStatus.FAILED, current(order).getStatus());
        Account account = service.accountSnapshot("BRK-1").orElseThrow();
        assertMoney("10000.00", account.getCash());
        assertEquals(5L, account.quantityByTicker("DEMO"));
    }

    @Test
    void buyExecutesAtTheLowerPriceWhenPriceFell() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "DEMO", 10);
        catalog.updatePrice("DEMO", new BigDecimal("300.00"));

        assertInstanceOf(OrderDecision.Confirmed.class, service.confirmOrder("BRK-1", order.getId()));

        assertMoney("7000.00", service.accountSnapshot("BRK-1").orElseThrow().getCash());
    }

    @Test
    void sellFailsWhenPriceFellByMoreThanAllowedButExecutesWhenItRose() {
        Order falling = placeOk("BRK-1", OrderSide.SELL, "TRNC", 1);
        catalog.updatePrice("TRNC", new BigDecimal("181.00")); // -2.29%
        assertEquals(Reason.PRICE_CHANGED, refusedReason(service.confirmOrder("BRK-1", falling.getId())));

        Order rising = placeOk("BRK-1", OrderSide.SELL, "TRNC", 1);
        catalog.updatePrice("TRNC", new BigDecimal("200.00"));
        assertInstanceOf(OrderDecision.Confirmed.class, service.confirmOrder("BRK-1", rising.getId()));

        assertMoney("10200.00", service.accountSnapshot("BRK-1").orElseThrow().getCash());
    }

    @Test
    void orderCanBeConfirmedUntilTheEndOfItsLifetime() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "DEMO", 1);
        clock.advance(TestBroker.PROPERTIES.orderTtl());

        assertInstanceOf(OrderDecision.Confirmed.class, service.confirmOrder("BRK-1", order.getId()));
    }

    @Test
    void expiredOrderCannotBeConfirmedOrRejectedAndAccountIsUntouched() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "DEMO", 1);
        clock.advance(TestBroker.PROPERTIES.orderTtl().plusSeconds(1));

        OrderDecision d = service.confirmOrder("BRK-1", order.getId());

        assertEquals(Reason.ORDER_EXPIRED, refusedReason(d));
        assertEquals(OrderStatus.EXPIRED, current(order).getStatus());
        assertEquals("Can't reject order with status = EXPIRED",
                refusedMessage(service.rejectOrder("BRK-1", order.getId())));
        assertMoney("10000.00", service.accountSnapshot("BRK-1").orElseThrow().getCash());
    }

    @Test
    void overdueOrderIsShownAsExpiredWhenRead() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "DEMO", 1);
        clock.advance(TestBroker.PROPERTIES.orderTtl().plusSeconds(1));

        assertEquals(OrderStatus.EXPIRED, service.orderById(order.getId()).orElseThrow().getStatus());
    }

    @Test
    void orderTimeComesFromTheClock() {
        assertEquals(clock.instant(), placeOk("BRK-1", OrderSide.BUY, "DEMO", 1).getCreatedAt());
    }

    // ---- placeOrder ----

    @Test
    void placeBuyCreatesPendingOrderWithoutMovingMoney() {
        OrderDecision d = service.placeOrder("BRK-1", OrderSide.BUY, "TRNC", 10);

        OrderDecision.Created created = assertInstanceOf(OrderDecision.Created.class, d);
        Order order = created.order();
        assertEquals(OrderStatus.PENDING_CONFIRMATION, current(order).getStatus());
        assertEquals("BRK-1", order.getAccountId());
        assertEquals(OrderSide.BUY, order.getSide());
        assertEquals(10, order.getQuantity());
        assertMoney("185.25", order.getPrice());
        assertMoney("8147.50", created.cashAfter());

        Account account = service.accountSnapshot("BRK-1").orElseThrow();
        assertMoney("10000.00", account.getCash());
        assertEquals(20L, account.quantityByTicker("TRNC"));
        assertEquals(OrderStatus.PENDING_CONFIRMATION, orders.byId(order.getId()).orElseThrow().getStatus());
    }

    @Test
    void placeSellCreatesPendingOrderWithoutMovingPosition() {
        OrderDecision.Created created = assertInstanceOf(OrderDecision.Created.class,
                service.placeOrder("BRK-1", OrderSide.SELL, "TRNC", 20));

        assertEquals(OrderStatus.PENDING_CONFIRMATION, created.order().getStatus());
        assertEquals(20L, service.accountSnapshot("BRK-1").orElseThrow().quantityByTicker("TRNC"));
    }

    @Test
    void placeSellCashAfterIncreasesByOrderTotal() {
        // 4 * 185.25 = 741.00 -> 10000.00 + 741.00
        OrderDecision.Created created = assertInstanceOf(OrderDecision.Created.class,
                service.placeOrder("BRK-1", OrderSide.SELL, "TRNC", 4));

        assertMoney("10741.00", created.cashAfter());
        assertMoney("10000.00", service.accountSnapshot("BRK-1").orElseThrow().getCash());
    }

    @Test
    void orderStoresCanonicalTickerRegardlessOfInputCase() {
        assertEquals("DEMO", placeOk("BRK-1", OrderSide.BUY, "demo", 1).getTicker());
        assertEquals("TRNC", placeOk("BRK-1", OrderSide.SELL, "tRnC", 1).getTicker());
    }

    @Test
    void buyWithLowercaseTickerAddsToExistingPosition() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "demo", 10);

        assertInstanceOf(OrderDecision.Confirmed.class, service.confirmOrder("BRK-1", order.getId()));

        Account account = service.accountSnapshot("BRK-1").orElseThrow();
        assertEquals(15L, account.quantityByTicker("DEMO"));
        assertFalse(account.getPositions().containsKey("demo"));
    }

    @Test
    void sellWithLowercaseTickerUsesExistingPosition() {
        OrderDecision.Refused refused = assertInstanceOf(OrderDecision.Refused.class,
                service.placeOrder("BRK-1", OrderSide.SELL, "trnc", 21));
        assertMoney("20", refused.available());

        Order order = placeOk("BRK-1", OrderSide.SELL, "trnc", 20);
        assertInstanceOf(OrderDecision.Confirmed.class, service.confirmOrder("BRK-1", order.getId()));
        assertEquals(0L, service.accountSnapshot("BRK-1").orElseThrow().quantityByTicker("TRNC"));
    }

    @Test
    void placeBuyWithExactlyAllCashIsAllowed() {
        // 100.00 cash, SMPL costs 12.30 -> 8 shares = 98.40; use TRNC-free exact case via BRK-1: 10000 / 410 not exact,
        // so verify boundary with DEMO: 24 * 410.00 = 9840.00 <= 10000, 25 * 410.00 = 10250.00 > 10000
        assertInstanceOf(OrderDecision.Created.class, service.placeOrder("BRK-1", OrderSide.BUY, "DEMO", 24));
        assertEquals(Reason.INSUFFICIENT_FUNDS,
                refusedReason(service.placeOrder("BRK-1", OrderSide.BUY, "DEMO", 25)));
    }

    @Test
    void placeBuyRefusedWhenNotEnoughFundsAndMessageHasNumbers() {
        OrderDecision d = service.placeOrder("BRK-2", OrderSide.BUY, "DEMO", 1);

        OrderDecision.Refused refused = assertInstanceOf(OrderDecision.Refused.class, d);
        assertEquals(Reason.INSUFFICIENT_FUNDS, refused.reason());
        assertTrue(refused.message().contains("410.00"), refused.message());
        assertTrue(refused.message().contains("100.00"), refused.message());
        assertTrue(orders.all().isEmpty());
    }

    @Test
    void placeBuyRefusalReportsShortfall() {
        // 30 * 410.00 = 12300.00 needed, 10000.00 available -> short by 2300.00
        OrderDecision.Refused refused = assertInstanceOf(OrderDecision.Refused.class,
                service.placeOrder("BRK-1", OrderSide.BUY, "DEMO", 30));

        assertEquals(Reason.INSUFFICIENT_FUNDS, refused.reason());
        assertMoney("12300.00", refused.needed());
        assertMoney("10000.00", refused.available());
        assertMoney("2300.00", refused.shortfall());
        assertEquals("Insufficient funds: needed 12300.00 USD, available 10000.00 USD, short by 2300.00 USD",
                refused.message());
    }

    @Test
    void placeSellRefusalReportsShortfall() {
        OrderDecision.Refused refused = assertInstanceOf(OrderDecision.Refused.class,
                service.placeOrder("BRK-1", OrderSide.SELL, "TRNC", 50));

        assertEquals(Reason.INSUFFICIENT_POSITION, refused.reason());
        assertMoney("50", refused.needed());
        assertMoney("20", refused.available());
        assertMoney("30", refused.shortfall());
        assertEquals("Insufficient securities: needed 50, available 20, short by 30", refused.message());
    }

    @Test
    void refusalsWithoutNumbersHaveNoAmounts() {
        OrderDecision.Refused refused = assertInstanceOf(OrderDecision.Refused.class,
                service.placeOrder("BRK-1", OrderSide.BUY, "NOPE", 1));

        assertNull(refused.needed());
        assertNull(refused.available());
        assertNull(refused.shortfall());
    }

    @Test
    void placeSellRefusedWhenQuantityExceedsPosition() {
        OrderDecision d = service.placeOrder("BRK-1", OrderSide.SELL, "TRNC", 21);

        OrderDecision.Refused refused = assertInstanceOf(OrderDecision.Refused.class, d);
        assertEquals(Reason.INSUFFICIENT_POSITION, refused.reason());
        assertTrue(refused.message().contains("20"), refused.message());
        assertTrue(orders.all().isEmpty());
    }

    @Test
    void placeSellRefusedWhenNoPositionAtAll() {
        assertEquals(Reason.INSUFFICIENT_POSITION,
                refusedReason(service.placeOrder("BRK-2", OrderSide.SELL, "TRNC", 1)));
    }

    @Test
    void placeRefusedForNonPositiveQuantity() {
        OrderDecision zero = service.placeOrder("BRK-1", OrderSide.BUY, "TRNC", 0);
        OrderDecision negative = service.placeOrder("BRK-1", OrderSide.SELL, "TRNC", -5);

        assertEquals(Reason.INVALID_QUANTITY, refusedReason(zero));
        assertEquals(Reason.INVALID_QUANTITY, refusedReason(negative));
        assertEquals("Quantity must be greater than 0", refusedMessage(zero));
        assertTrue(orders.all().isEmpty());
    }

    @Test
    void placeRefusedForUnknownTicker() {
        OrderDecision d = service.placeOrder("BRK-1", OrderSide.BUY, "NOPE", 1);

        assertEquals(Reason.UNKNOWN_SECURITY, refusedReason(d));
        assertEquals("Security with ticker = NOPE not found", refusedMessage(d));
    }

    @Test
    void placeRefusedForUnknownAccount() {
        OrderDecision d = service.placeOrder("nope", OrderSide.BUY, "TRNC", 1);

        assertEquals(Reason.ILLEGAL_ACCESS, refusedReason(d));
        assertEquals("Account with accountId = nope not found", refusedMessage(d));
    }

    @Test
    void placedOrdersGetDistinctIds() {
        Order a = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);
        Order b = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);
        assertNotEquals(a.getId(), b.getId());
    }

    // ---- confirmOrder ----

    @Test
    void confirmBuyExecutesAndUpdatesAccount() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 10);

        OrderDecision d = service.confirmOrder("BRK-1", order.getId());

        OrderDecision.Confirmed confirmed = assertInstanceOf(OrderDecision.Confirmed.class, d);
        assertEquals(OrderStatus.EXECUTED, confirmed.order().getStatus());
        Account account = service.accountSnapshot("BRK-1").orElseThrow();
        assertMoney("8147.50", account.getCash());
        assertEquals(30L, account.quantityByTicker("TRNC"));
    }

    @Test
    void confirmSellExecutesAndUpdatesAccount() {
        Order order = placeOk("BRK-1", OrderSide.SELL, "TRNC", 4);

        assertInstanceOf(OrderDecision.Confirmed.class, service.confirmOrder("BRK-1", order.getId()));

        Account account = service.accountSnapshot("BRK-1").orElseThrow();
        assertMoney("10741.00", account.getCash());
        assertEquals(16L, account.quantityByTicker("TRNC"));
        assertEquals(OrderStatus.EXECUTED, current(order).getStatus());
    }

    @Test
    void confirmTwiceDoesNotChangeAccountSecondTime() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 10);
        service.confirmOrder("BRK-1", order.getId());

        OrderDecision second = service.confirmOrder("BRK-1", order.getId());

        assertEquals(Reason.ILLEGAL_ACCESS, refusedReason(second));
        assertEquals("Can't confirm order with status = EXECUTED", refusedMessage(second));
        Account account = service.accountSnapshot("BRK-1").orElseThrow();
        assertMoney("8147.50", account.getCash());
        assertEquals(30L, account.quantityByTicker("TRNC"));
    }

    @Test
    void confirmBuyRechecksFundsAtConfirmTime() {
        // BRK-2 cash 100.00, SMPL 12.30: two pending orders of 8 shares (98.40 each) both pass at placement.
        Order first = placeOk("BRK-2", OrderSide.BUY, "SMPL", 8);
        Order second = placeOk("BRK-2", OrderSide.BUY, "SMPL", 8);

        assertInstanceOf(OrderDecision.Confirmed.class, service.confirmOrder("BRK-2", first.getId()));
        OrderDecision d = service.confirmOrder("BRK-2", second.getId());

        assertEquals(Reason.INSUFFICIENT_FUNDS, refusedReason(d));
        // 98.40 needed, 1.60 left after the first order -> short by 96.80
        assertMoney("96.80", ((OrderDecision.Refused) d).shortfall());
        Account account = service.accountSnapshot("BRK-2").orElseThrow();
        assertMoney("1.60", account.getCash());
        assertEquals(18L, account.quantityByTicker("SMPL"));
        assertEquals(OrderStatus.FAILED, current(second).getStatus());
        assertEquals(d instanceof OrderDecision.Refused r ? r.message() : null, current(second).getReason());
    }

    @Test
    void confirmSellRechecksPositionAtConfirmTime() {
        Order first = placeOk("BRK-2", OrderSide.SELL, "SMPL", 8);
        Order second = placeOk("BRK-2", OrderSide.SELL, "SMPL", 8);

        assertInstanceOf(OrderDecision.Confirmed.class, service.confirmOrder("BRK-2", first.getId()));
        OrderDecision d = service.confirmOrder("BRK-2", second.getId());

        assertEquals(Reason.INSUFFICIENT_POSITION, refusedReason(d));
        assertEquals(2L, service.accountSnapshot("BRK-2").orElseThrow().quantityByTicker("SMPL"));
        assertEquals(OrderStatus.FAILED, current(second).getStatus());
    }

    @Test
    void failedOrderCannotBeConfirmedAgain() {
        Order first = placeOk("BRK-2", OrderSide.SELL, "SMPL", 8);
        Order second = placeOk("BRK-2", OrderSide.SELL, "SMPL", 8);
        service.confirmOrder("BRK-2", first.getId());
        service.confirmOrder("BRK-2", second.getId());

        OrderDecision again = service.confirmOrder("BRK-2", second.getId());

        assertEquals("Can't confirm order with status = FAILED", refusedMessage(again));
    }

    @Test
    void confirmFailsWithoutFreeTradeWhenTickerIsUnknown() {
        Order ghost = new Order("ORD-GHOST", "BRK-1", OrderSide.BUY, "GHOST", 10, new BigDecimal("1.00"),
                BigDecimal.ZERO, clock.instant());
        orders.add(ghost);

        OrderDecision d = service.confirmOrder("BRK-1", ghost.getId());

        assertEquals(Reason.UNKNOWN_SECURITY, refusedReason(d));
        assertEquals(OrderStatus.FAILED, current(ghost).getStatus());
        Account account = service.accountSnapshot("BRK-1").orElseThrow();
        assertMoney("10000.00", account.getCash());
        assertEquals(0L, account.quantityByTicker("GHOST"));
    }

    @Test
    void confirmRefusedForUnknownOrder() {
        OrderDecision d = service.confirmOrder("BRK-1", "ORD-999");

        assertEquals(Reason.ORDER_NOT_FOUND, refusedReason(d));
        assertEquals("Order ORD-999 not found", refusedMessage(d));
    }

    @Test
    void confirmRefusedForUnknownAccount() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);

        OrderDecision d = service.confirmOrder("nope", order.getId());

        assertEquals(Reason.ORDER_NOT_FOUND, refusedReason(d));
        assertEquals("Order " + order.getId() + " not found", refusedMessage(d));
        assertEquals(OrderStatus.PENDING_CONFIRMATION, current(order).getStatus());
    }

    @Test
    void confirmRefusedForForeignAccountAndNothingChanges() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);

        OrderDecision d = service.confirmOrder("BRK-2", order.getId());

        // The same refusal as for a missing order: another account's order ids cannot be probed.
        assertEquals(Reason.ORDER_NOT_FOUND, refusedReason(d));
        assertEquals("Order " + order.getId() + " not found", refusedMessage(d));

        assertEquals(OrderStatus.PENDING_CONFIRMATION, current(order).getStatus());
        assertMoney("10000.00", service.accountSnapshot("BRK-1").orElseThrow().getCash());
        assertMoney("100.00", service.accountSnapshot("BRK-2").orElseThrow().getCash());
    }

    @Test
    void confirmRefusedForRejectedOrder() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);
        service.rejectOrder("BRK-1", order.getId());

        OrderDecision d = service.confirmOrder("BRK-1", order.getId());

        assertEquals(Reason.ILLEGAL_ACCESS, refusedReason(d));
        assertEquals("Can't confirm order with status = REJECTED_BY_USER", refusedMessage(d));
        assertEquals(OrderStatus.REJECTED_BY_USER, current(order).getStatus());
        assertMoney("10000.00", service.accountSnapshot("BRK-1").orElseThrow().getCash());
    }

    // ---- cancelUnseenOrder ----

    @Test
    void unseenOrderIsCancelledBySystemNotRejectedByUser() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);

        assertTrue(service.cancelUnseenOrder("BRK-1", order.getId(), "chat failed"));

        assertEquals(OrderStatus.CANCELLED, current(order).getStatus());
        assertEquals("chat failed", current(order).getReason());
        assertEquals("Can't confirm order with status = CANCELLED",
                refusedMessage(service.confirmOrder("BRK-1", order.getId())));
    }

    @Test
    void cancelUnseenOrderLeavesForeignMissingAndDecidedOrdersAlone() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);
        Order executed = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);
        service.confirmOrder("BRK-1", executed.getId());

        assertFalse(service.cancelUnseenOrder("BRK-2", order.getId(), "x"));
        assertFalse(service.cancelUnseenOrder("BRK-1", "ORD-999", "x"));
        assertFalse(service.cancelUnseenOrder("BRK-1", executed.getId(), "x"));

        assertEquals(OrderStatus.PENDING_CONFIRMATION, current(order).getStatus());
        assertEquals(OrderStatus.EXECUTED, current(executed).getStatus());
    }

    // ---- rejectOrder ----

    @Test
    void rejectPendingOrderMarksItRejectedWithoutMovingMoney() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 10);

        OrderDecision d = service.rejectOrder("BRK-1", order.getId());

        OrderDecision.Rejected rejected = assertInstanceOf(OrderDecision.Rejected.class, d);
        assertEquals(OrderStatus.REJECTED_BY_USER, rejected.order().getStatus());
        Account account = service.accountSnapshot("BRK-1").orElseThrow();
        assertMoney("10000.00", account.getCash());
        assertEquals(20L, account.quantityByTicker("TRNC"));
    }

    @Test
    void rejectDoesNotChangeExecutedOrder() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);
        service.confirmOrder("BRK-1", order.getId());

        OrderDecision d = service.rejectOrder("BRK-1", order.getId());

        assertEquals(Reason.ILLEGAL_ACCESS, refusedReason(d));
        assertEquals("Can't reject order with status = EXECUTED", refusedMessage(d));
        assertEquals(OrderStatus.EXECUTED, current(order).getStatus());
    }

    @Test
    void rejectTwiceIsRefused() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);
        service.rejectOrder("BRK-1", order.getId());

        OrderDecision d = service.rejectOrder("BRK-1", order.getId());

        assertEquals(Reason.ILLEGAL_ACCESS, refusedReason(d));
        assertEquals("Can't reject order with status = REJECTED_BY_USER", refusedMessage(d));
    }

    @Test
    void rejectRefusedForUnknownOrderUnknownAccountAndForeignAccount() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);

        OrderDecision unknownOrder = service.rejectOrder("BRK-1", "ORD-999");
        OrderDecision unknownAccount = service.rejectOrder("nope", order.getId());
        OrderDecision foreignAccount = service.rejectOrder("BRK-2", order.getId());

        assertEquals(Reason.ORDER_NOT_FOUND, refusedReason(unknownOrder));
        assertEquals(Reason.ORDER_NOT_FOUND, refusedReason(unknownAccount));
        assertEquals(Reason.ORDER_NOT_FOUND, refusedReason(foreignAccount));
        assertEquals("Order ORD-999 not found", refusedMessage(unknownOrder));
        assertEquals("Order " + order.getId() + " not found", refusedMessage(foreignAccount));
        assertEquals(OrderStatus.PENDING_CONFIRMATION, current(order).getStatus());
    }

    // ---- findSecurity ----

    @Test
    void findByExactTickerIsCaseInsensitive() {
        SearchResult r = service.findSecurity("smpl");
        assertEquals(Match.EXACT, r.match());
        assertEquals("SMPL", r.securities().getFirst().ticker());
    }

    @Test
    void findByIsin() {
        SearchResult r = service.findSecurity("US0000DEMO07");
        assertEquals(Match.EXACT, r.match());
        assertEquals("DEMO", r.securities().getFirst().ticker());
    }

    @Test
    void findByUniqueNameFragment() {
        SearchResult r = service.findSecurity("logistics");
        assertEquals(Match.EXACT, r.match());
        assertEquals("SMPL", r.securities().getFirst().ticker());
    }

    @Test
    void findByAmbiguousNameReturnsCandidates() {
        SearchResult r = service.findSecurity("Training Corp");

        assertEquals(Match.CANDIDATES, r.match());
        assertEquals(2, r.securities().size());
    }

    @Test
    void findUnknownReturnsNotFound() {
        SearchResult r = service.findSecurity("zzz-nothing");
        assertEquals(Match.NOT_FOUND, r.match());
        assertTrue(r.securities().isEmpty());
    }

    @Test
    void findByTickerWhenIsinAndNameDoNotMatch() {
        // In the demo data every ISIN contains its ticker, so the ticker-only branch needs a custom catalog.
        Security odd = new Security("ABCD", "XX9999999999", "Unrelated Name", new BigDecimal("1.00"));
        SecurityCatalog oddCatalog = new SecurityCatalog() {
            @Override
            public java.util.List<Security> all() {
                return java.util.List.of(odd);
            }
        };
        BrokerService custom = TestBroker.with(oddCatalog, new OrderBook(), clock);

        SearchResult r = custom.findSecurity("abcd");

        assertEquals(Match.EXACT, r.match());
        assertSame(odd, r.securities().getFirst());
    }

    @Test
    void findWithNullQueryReturnsNotFound() {
        assertEquals(Match.NOT_FOUND, service.findSecurity(null).match());
    }

    @Test
    void findWithEmptyOrBlankQueryReturnsNotFound() {
        for (String query : new String[]{"", "   ", "\t", ".", " . . "}) {
            SearchResult r = service.findSecurity(query);
            assertEquals(Match.NOT_FOUND, r.match(), "query: '" + query + "'");
            assertTrue(r.securities().isEmpty());
        }
    }

    @Test
    void findByIsinIsCaseInsensitiveAndTrimmed() {
        SearchResult r = service.findSecurity("  us0000trnp02 ");

        assertEquals(Match.EXACT, r.match());
        assertEquals("TRNP", r.securities().getFirst().ticker());
    }

    @Test
    void findDoesNotMatchPartialTickerOrIsin() {
        assertEquals(Match.NOT_FOUND, service.findSecurity("TRN").match());
        assertEquals(Match.NOT_FOUND, service.findSecurity("0000").match());
        assertEquals(Match.NOT_FOUND, service.findSecurity("US0000TRN").match());
    }

    @Test
    void findNormalizesCaseSpacesAndDotsInName() {
        SearchResult spaces = service.findSecurity("  sample   LOGISTICS  ltd. ");
        assertEquals(Match.EXACT, spaces.match());
        assertEquals("SMPL", spaces.securities().getFirst().ticker());

        SearchResult dotInQuery = service.findSecurity("Sample Logistics Ltd");
        assertEquals(Match.EXACT, dotInQuery.match());
        assertEquals("SMPL", dotInQuery.securities().getFirst().ticker());

        SearchResult dotAcrossWords = service.findSecurity("corp common");
        assertEquals(Match.EXACT, dotAcrossWords.match());
        assertEquals("TRNC", dotAcrossWords.securities().getFirst().ticker());
    }

    @Test
    void findByTrainingReturnsThreeCandidates() {
        SearchResult r = service.findSecurity("Training");

        assertEquals(Match.CANDIDATES, r.match());
        assertEquals(java.util.List.of("TRNC", "TRNP", "TREN"),
                r.securities().stream().map(Security::ticker).toList());
    }

    @Test
    void findBySampleBankIsSingleMatch() {
        SearchResult r = service.findSecurity("Sample Bank");

        assertEquals(Match.EXACT, r.match());
        assertEquals("SMPB", r.securities().getFirst().ticker());
    }

    @Test
    void findExactTickerWinsOverNameCandidates() {
        Security alpha = new Security("TEST", "XX0000000001", "Test Alpha Inc.", new BigDecimal("1.00"));
        Security beta = new Security("TSTB", "XX0000000002", "Test Beta Inc.", new BigDecimal("2.00"));
        SecurityCatalog oddCatalog = new SecurityCatalog() {
            @Override
            public java.util.List<Security> all() {
                return java.util.List.of(alpha, beta);
            }
        };
        BrokerService custom = TestBroker.with(oddCatalog, new OrderBook(), clock);

        SearchResult byTicker = custom.findSecurity("test");
        assertEquals(Match.EXACT, byTicker.match());
        assertSame(alpha, byTicker.securities().getFirst());

        // while a shared part of the names that is not a ticker still gives both candidates
        assertEquals(Match.CANDIDATES, custom.findSecurity("Inc").match());
    }
}
