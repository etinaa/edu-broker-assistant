package ai.lab.broker.broker;

import ai.lab.broker.domain.*;
import ai.lab.broker.domain.OrderDecision.Reason;
import ai.lab.broker.domain.SearchResult.Match;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Uses the real in-memory collaborators with their demo data:
 * BRK-1: cash 10000.00, TRNC=20, SMPB=100, DEMO=5; BRK-2: cash 100.00, SMPL=10.
 * Prices: TRNC 185.25, SMPB 41.75, SMPL 12.30, DEMO 410.00.
 */
class BrokerServiceTest {

    private OrderBook orders;
    private BrokerService service;

    @BeforeEach
    void setUp() {
        orders = new OrderBook();
        service = new BrokerService(new SecurityCatalog(), new AccountRepository(), orders);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private Order placeOk(String accountId, OrderSide side, String ticker, long qty) {
        OrderDecision d = service.placeOrder(accountId, side, ticker, qty);
        return assertInstanceOf(OrderDecision.Created.class, d).order();
    }

    private static Reason refusedReason(OrderDecision d) {
        return assertInstanceOf(OrderDecision.Refused.class, d).reason();
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
        assertTrue(service.accountById("BRK-1").isPresent());
        assertTrue(service.accountById("nope").isEmpty());
        assertTrue(service.orderById("ORD-1").isEmpty());

        Order o = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);
        assertSame(o, service.orderById(o.getId()).orElseThrow());
    }

    // ---- placeOrder ----

    @Test
    void placeBuyCreatesPendingOrderWithoutMovingMoney() {
        OrderDecision d = service.placeOrder("BRK-1", OrderSide.BUY, "TRNC", 10);

        OrderDecision.Created created = assertInstanceOf(OrderDecision.Created.class, d);
        Order order = created.order();
        assertEquals(OrderStatus.PENDING_CONFIRMATION, order.getStatus());
        assertEquals("BRK-1", order.getAccountId());
        assertEquals(OrderSide.BUY, order.getSide());
        assertEquals(10, order.getQuantity());
        assertMoney("185.25", order.getPrice());
        assertMoney("8147.50", created.cashAfter());

        Account account = service.accountById("BRK-1").orElseThrow();
        assertMoney("10000.00", account.getCash());
        assertEquals(20L, account.quantityByTicker("TRNC"));
        assertSame(order, orders.byId(order.getId()).orElseThrow());
    }

    @Test
    void placeSellCreatesPendingOrderWithoutMovingPosition() {
        OrderDecision.Created created = assertInstanceOf(OrderDecision.Created.class,
                service.placeOrder("BRK-1", OrderSide.SELL, "TRNC", 20));

        assertEquals(OrderStatus.PENDING_CONFIRMATION, created.order().getStatus());
        assertEquals(20L, service.accountById("BRK-1").orElseThrow().quantityByTicker("TRNC"));
    }

    @Test
    void placeSellCashAfterIncreasesByOrderTotal() {
        // 4 * 185.25 = 741.00 -> 10000.00 + 741.00
        OrderDecision.Created created = assertInstanceOf(OrderDecision.Created.class,
                service.placeOrder("BRK-1", OrderSide.SELL, "TRNC", 4));

        assertMoney("10741.00", created.cashAfter());
        assertMoney("10000.00", service.accountById("BRK-1").orElseThrow().getCash());
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
        assertEquals(Reason.INVALID_QUANTITY, refusedReason(service.placeOrder("BRK-1", OrderSide.BUY, "TRNC", 0)));
        assertEquals(Reason.INVALID_QUANTITY, refusedReason(service.placeOrder("BRK-1", OrderSide.SELL, "TRNC", -5)));
        assertTrue(orders.all().isEmpty());
    }

    @Test
    void placeRefusedForUnknownTicker() {
        assertEquals(Reason.UNKNOWN_SECURITY, refusedReason(service.placeOrder("BRK-1", OrderSide.BUY, "NOPE", 1)));
    }

    @Test
    void placeRefusedForUnknownAccount() {
        assertEquals(Reason.ILLEGAL_ACCESS, refusedReason(service.placeOrder("nope", OrderSide.BUY, "TRNC", 1)));
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
        Account account = service.accountById("BRK-1").orElseThrow();
        assertMoney("8147.50", account.getCash());
        assertEquals(30L, account.quantityByTicker("TRNC"));
    }

    @Test
    void confirmSellExecutesAndUpdatesAccount() {
        Order order = placeOk("BRK-1", OrderSide.SELL, "TRNC", 4);

        assertInstanceOf(OrderDecision.Confirmed.class, service.confirmOrder("BRK-1", order.getId()));

        Account account = service.accountById("BRK-1").orElseThrow();
        assertMoney("10741.00", account.getCash());
        assertEquals(16L, account.quantityByTicker("TRNC"));
        assertEquals(OrderStatus.EXECUTED, order.getStatus());
    }

    @Test
    void confirmTwiceDoesNotChangeAccountSecondTime() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 10);
        service.confirmOrder("BRK-1", order.getId());

        OrderDecision second = service.confirmOrder("BRK-1", order.getId());

        assertEquals(Reason.ILLEGAL_ACCESS, refusedReason(second));
        Account account = service.accountById("BRK-1").orElseThrow();
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
        Account account = service.accountById("BRK-2").orElseThrow();
        assertMoney("1.60", account.getCash());
        assertEquals(18L, account.quantityByTicker("SMPL"));
        assertNotEquals(OrderStatus.EXECUTED, second.getStatus());
    }

    @Test
    void confirmSellRechecksPositionAtConfirmTime() {
        Order first = placeOk("BRK-2", OrderSide.SELL, "SMPL", 8);
        Order second = placeOk("BRK-2", OrderSide.SELL, "SMPL", 8);

        assertInstanceOf(OrderDecision.Confirmed.class, service.confirmOrder("BRK-2", first.getId()));
        OrderDecision d = service.confirmOrder("BRK-2", second.getId());

        assertEquals(Reason.INSUFFICIENT_POSITION, refusedReason(d));
        assertEquals(2L, service.accountById("BRK-2").orElseThrow().quantityByTicker("SMPL"));
    }

    @Test
    void confirmRefusedForUnknownOrder() {
        assertEquals(Reason.ILLEGAL_ACCESS, refusedReason(service.confirmOrder("BRK-1", "ORD-999")));
    }

    @Test
    void confirmRefusedForUnknownAccount() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);
        assertEquals(Reason.ILLEGAL_ACCESS, refusedReason(service.confirmOrder("nope", order.getId())));
        assertEquals(OrderStatus.PENDING_CONFIRMATION, order.getStatus());
    }

    @Test
    void confirmRefusedForForeignAccountAndNothingChanges() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);

        assertEquals(Reason.ILLEGAL_ACCESS, refusedReason(service.confirmOrder("BRK-2", order.getId())));

        assertEquals(OrderStatus.PENDING_CONFIRMATION, order.getStatus());
        assertMoney("10000.00", service.accountById("BRK-1").orElseThrow().getCash());
        assertMoney("100.00", service.accountById("BRK-2").orElseThrow().getCash());
    }

    @Test
    void confirmRefusedForRejectedOrder() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);
        service.rejectOrder("BRK-1", order.getId());

        assertEquals(Reason.ILLEGAL_ACCESS, refusedReason(service.confirmOrder("BRK-1", order.getId())));
        assertEquals(OrderStatus.REJECTED_BY_USER, order.getStatus());
        assertMoney("10000.00", service.accountById("BRK-1").orElseThrow().getCash());
    }

    // ---- rejectOrder ----

    @Test
    void rejectPendingOrderMarksItRejectedWithoutMovingMoney() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 10);

        OrderDecision d = service.rejectOrder("BRK-1", order.getId());

        OrderDecision.Rejected rejected = assertInstanceOf(OrderDecision.Rejected.class, d);
        assertEquals(OrderStatus.REJECTED_BY_USER, rejected.order().getStatus());
        Account account = service.accountById("BRK-1").orElseThrow();
        assertMoney("10000.00", account.getCash());
        assertEquals(20L, account.quantityByTicker("TRNC"));
    }

    @Test
    void rejectDoesNotChangeExecutedOrder() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);
        service.confirmOrder("BRK-1", order.getId());

        assertEquals(Reason.ILLEGAL_ACCESS, refusedReason(service.rejectOrder("BRK-1", order.getId())));
        assertEquals(OrderStatus.EXECUTED, order.getStatus());
    }

    @Test
    void rejectTwiceIsRefused() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);
        service.rejectOrder("BRK-1", order.getId());

        assertEquals(Reason.ILLEGAL_ACCESS, refusedReason(service.rejectOrder("BRK-1", order.getId())));
    }

    @Test
    void rejectRefusedForUnknownOrderUnknownAccountAndForeignAccount() {
        Order order = placeOk("BRK-1", OrderSide.BUY, "TRNC", 1);

        assertEquals(Reason.ILLEGAL_ACCESS, refusedReason(service.rejectOrder("BRK-1", "ORD-999")));
        assertEquals(Reason.ILLEGAL_ACCESS, refusedReason(service.rejectOrder("nope", order.getId())));
        assertEquals(Reason.ILLEGAL_ACCESS, refusedReason(service.rejectOrder("BRK-2", order.getId())));
        assertEquals(OrderStatus.PENDING_CONFIRMATION, order.getStatus());
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
        SecurityCatalog catalog = new SecurityCatalog() {
            @Override
            public java.util.List<Security> all() {
                return java.util.List.of(odd);
            }
        };
        BrokerService custom = new BrokerService(catalog, new AccountRepository(), new OrderBook());

        SearchResult r = custom.findSecurity("abcd");

        assertEquals(Match.EXACT, r.match());
        assertSame(odd, r.securities().getFirst());
    }

    @Test
    void findWithNullQueryReturnsNotFound() {
        assertEquals(Match.NOT_FOUND, service.findSecurity(null).match());
    }
}
