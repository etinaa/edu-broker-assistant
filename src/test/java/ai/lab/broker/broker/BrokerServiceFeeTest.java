package ai.lab.broker.broker;

import ai.lab.broker.config.BrokerProperties;
import ai.lab.broker.domain.Account;
import ai.lab.broker.domain.Order;
import ai.lab.broker.domain.OrderDecision;
import ai.lab.broker.domain.OrderSide;
import ai.lab.broker.domain.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The broker fee (task 10) on the demo data: BRK-1 cash 10000.00, TRNC=20; BRK-2 cash 100.00, SMPL=10.
 * Prices: TRNC 185.25, SMPB 41.75, SMPL 12.30, DEMO 410.00.
 */
class BrokerServiceFeeTest {

    private final SecurityCatalog catalog = new SecurityCatalog();

    private BrokerService broker(String feePercent, String minFee) {
        BrokerProperties p = TestBroker.PROPERTIES;
        BrokerProperties withFee = new BrokerProperties(p.orderTtl(), p.maxPriceMovePercent(), p.maxOrders(),
                new BigDecimal(feePercent), new BigDecimal(minFee));
        return TestBroker.with(catalog, new OrderBook(), new MutableClock(), withFee);
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static OrderDecision.Created created(OrderDecision d) {
        return assertInstanceOf(OrderDecision.Created.class, d);
    }

    private static Account account(BrokerService broker, String id) {
        return broker.accountSnapshot(id).orElseThrow();
    }

    @Test
    void buyPaysAmountPlusFee() {
        BrokerService broker = broker("0.1", "0");

        // 10 × 410.00 = 4100.00, fee 0.1% = 4.10
        OrderDecision.Created c = created(broker.placeOrder("BRK-1", OrderSide.BUY, "DEMO", 10));
        Order order = c.order();
        assertMoney("4100.00", order.amount());
        assertMoney("4.10", order.getFee());
        assertMoney("4104.10", order.total());
        assertMoney("5895.90", c.cashAfter());

        broker.confirmOrder("BRK-1", order.getId());
        assertMoney("5895.90", account(broker, "BRK-1").getCash());
        assertEquals(15L, account(broker, "BRK-1").quantityByTicker("DEMO"));
    }

    @Test
    void sellReceivesAmountMinusFee() {
        BrokerService broker = broker("0.1", "0");

        // 4 × 185.25 = 741.00, fee 0.74
        OrderDecision.Created c = created(broker.placeOrder("BRK-1", OrderSide.SELL, "TRNC", 4));
        assertMoney("0.74", c.order().getFee());
        assertMoney("740.26", c.order().total());
        assertMoney("10740.26", c.cashAfter());

        broker.confirmOrder("BRK-1", c.order().getId());
        assertMoney("10740.26", account(broker, "BRK-1").getCash());
        assertEquals(16L, account(broker, "BRK-1").quantityByTicker("TRNC"));
        assertMoney("740.26", broker.orderById(c.order().getId()).orElseThrow().executedTotal());
    }

    @Test
    void anOrderThatIsNotExecutedHasNoExecutedTotal() {
        BrokerService broker = broker("0.1", "0");
        Order order = created(broker.placeOrder("BRK-1", OrderSide.BUY, "DEMO", 1)).order();
        broker.rejectOrder("BRK-1", order.getId());

        Order rejected = broker.orderById(order.getId()).orElseThrow();
        assertNull(rejected.getExecutedPrice());
        assertNull(rejected.executedTotal());
    }

    @Test
    void theFeeCanMakeAnAffordableAmountUnaffordable() {
        BrokerService broker = broker("1", "0");

        // 239 × 41.75 = 9978.25 fits in 10000.00, but with the 1% fee (99.78) it is 10078.03.
        OrderDecision.Refused r = assertInstanceOf(OrderDecision.Refused.class,
                broker.placeOrder("BRK-1", OrderSide.BUY, "SMPB", 239));
        assertEquals(OrderDecision.Reason.INSUFFICIENT_FUNDS, r.reason());
        assertMoney("10078.03", r.needed());
        assertMoney("10000.00", r.available());
        assertMoney("78.03", r.shortfall());
        assertTrue(r.message().contains("including a fee of 99.78 USD"), r.message());
    }

    @Test
    void refusalWithoutFeeDoesNotMentionIt() {
        BrokerService broker = broker("0", "0");

        OrderDecision.Refused r = assertInstanceOf(OrderDecision.Refused.class,
                broker.placeOrder("BRK-1", OrderSide.BUY, "DEMO", 30));
        assertEquals("Insufficient funds: needed 12300.00 USD, available 10000.00 USD, short by 2300.00 USD",
                r.message());
    }

    @Test
    void minimumFeeAppliesToSmallOrders() {
        BrokerService broker = broker("0.1", "1.00");

        // 1 × 12.30: 0.1% is 0.01, the minimum is 1.00
        OrderDecision.Created c = created(broker.placeOrder("BRK-1", OrderSide.BUY, "SMPL", 1));
        assertMoney("1.00", c.order().getFee());
        assertMoney("13.30", c.order().total());
    }

    @Test
    void theFeeNeverExceedsTheAmountSoASaleCostsNothing() {
        BrokerService broker = broker("0", "20.00");

        // 1 × 12.30 with a minimum fee of 20.00: the fee is capped at 12.30, the client gets 0.00
        OrderDecision.Created c = created(broker.placeOrder("BRK-2", OrderSide.SELL, "SMPL", 1));
        assertMoney("12.30", c.order().getFee());
        assertMoney("0.00", c.order().total());
        assertMoney("100.00", c.cashAfter());
    }

    @Test
    void confirmRecomputesTheFeeAtTheCurrentPrice() {
        BrokerService broker = broker("0.1", "0");
        Order order = created(broker.placeOrder("BRK-1", OrderSide.BUY, "DEMO", 10)).order();

        // +8.00 is within the 2% limit: executed at 418.00, amount 4180.00, fee 4.18
        catalog.updatePrice("DEMO", new BigDecimal("418.00"));
        Order executed = assertInstanceOf(OrderDecision.Confirmed.class,
                broker.confirmOrder("BRK-1", order.getId())).order();

        assertEquals(OrderStatus.EXECUTED, executed.getStatus());
        assertEquals("Order confirmed at 418.00 USD, fee 4.18 USD", executed.getReason());
        assertMoney("5815.82", account(broker, "BRK-1").getCash());

        // The order keeps its quote and records what was actually charged, matching the cash.
        assertMoney("410.00", executed.getPrice());
        assertMoney("4104.10", executed.total());
        assertMoney("418.00", executed.getExecutedPrice());
        assertMoney("4.18", executed.getExecutedFee());
        assertMoney("4184.18", executed.executedTotal());
        assertMoney("4184.18", broker.orderById(order.getId()).orElseThrow().executedTotal());
    }

    @Test
    void confirmFailsWhenTheFeeNoLongerFits() {
        BrokerService broker = broker("1", "0");

        // 24 × 410.00 = 9840.00 + 98.40 = 9938.40 fits; at 418.00: 10032.00 + 100.32 does not
        Order order = created(broker.placeOrder("BRK-1", OrderSide.BUY, "DEMO", 24)).order();
        catalog.updatePrice("DEMO", new BigDecimal("418.00"));

        OrderDecision.Refused r = assertInstanceOf(OrderDecision.Refused.class,
                broker.confirmOrder("BRK-1", order.getId()));
        assertEquals(OrderDecision.Reason.INSUFFICIENT_FUNDS, r.reason());
        assertMoney("10132.32", r.needed());
        assertMoney("10000.00", account(broker, "BRK-1").getCash());
    }
}
