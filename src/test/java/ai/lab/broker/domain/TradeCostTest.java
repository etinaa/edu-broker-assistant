package ai.lab.broker.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeCostTest {

    private static final BigDecimal PRICE = new BigDecimal("410.00");

    @Test
    void buyPaysAmountPlusFee() {
        TradeCost cost = TradeCost.of(OrderSide.BUY, 10, PRICE, amount -> new BigDecimal("4.10"));

        assertEquals(new BigDecimal("4100.00"), cost.amount());
        assertEquals(new BigDecimal("4.10"), cost.fee());
        assertEquals(new BigDecimal("4104.10"), cost.total());
    }

    @Test
    void sellReceivesAmountMinusFee() {
        TradeCost cost = TradeCost.of(OrderSide.SELL, 10, PRICE, amount -> new BigDecimal("4.10"));

        assertEquals(new BigDecimal("4095.90"), cost.total());
    }

    @Test
    void theFeeRuleGetsTheAmount() {
        TradeCost cost = TradeCost.of(OrderSide.BUY, 2, PRICE, amount -> amount.movePointLeft(3));

        assertEquals(new BigDecimal("0.82000"), cost.fee());
    }
}
