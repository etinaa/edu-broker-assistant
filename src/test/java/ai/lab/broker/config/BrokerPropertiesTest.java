package ai.lab.broker.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class BrokerPropertiesTest {

    /** Rules without a fee. */
    private static BrokerProperties rules(Duration orderTtl, BigDecimal maxPriceMovePercent, int maxOrders) {
        return new BrokerProperties(orderTtl, maxPriceMovePercent, maxOrders, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void nonPositiveLifetimeOrNegativePriceMoveIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> rules(Duration.ZERO, BigDecimal.ONE, 100));
        assertThrows(IllegalArgumentException.class, () -> rules(null, BigDecimal.ONE, 100));
        assertThrows(IllegalArgumentException.class,
                () -> rules(Duration.ofMinutes(5), new BigDecimal("-1"), 100));
    }

    @Test
    void nonPositiveMaxOrdersIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> rules(Duration.ofMinutes(5), BigDecimal.ONE, 0));
    }

    @Test
    void zeroPriceMoveIsAllowed() {
        assertDoesNotThrow(() -> rules(Duration.ofMinutes(5), BigDecimal.ZERO, 100));
    }

    @Test
    void negativeFeeIsRejectedAndMissingFeeIsZero() {
        Duration ttl = Duration.ofMinutes(5);
        assertThrows(IllegalArgumentException.class,
                () -> new BrokerProperties(ttl, BigDecimal.ONE, 100, new BigDecimal("-0.1"), BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new BrokerProperties(ttl, BigDecimal.ONE, 100, BigDecimal.ZERO, new BigDecimal("-1")));

        BrokerProperties none = new BrokerProperties(ttl, BigDecimal.ONE, 100, null, null);
        assertEquals(new BigDecimal("0.00"), none.fee(new BigDecimal("4100.00")));
    }

    @Test
    void feeIsRoundedHalfUpToCentsWithAMinimumAndNeverAboveTheAmount() {
        BrokerProperties p = new BrokerProperties(Duration.ofMinutes(5), BigDecimal.ONE, 100,
                new BigDecimal("0.1"), new BigDecimal("1.00"));

        assertEquals(new BigDecimal("9.82"), p.fee(new BigDecimal("9818.25")));  // 9.81825
        assertEquals(new BigDecimal("1.03"), p.fee(new BigDecimal("1025.00")));  // 1.025 → 1.03
        assertEquals(new BigDecimal("1.00"), p.fee(new BigDecimal("12.30")));    // 0.0123 → the minimum
        assertEquals(new BigDecimal("0.50"), p.fee(new BigDecimal("0.50")));     // capped at the amount
    }

    @Test
    void theCapIsAppliedAfterRoundingSoTheFeeNeverExceedsASubCentAmount() {
        BrokerProperties p = new BrokerProperties(Duration.ofMinutes(5), BigDecimal.ONE, 100,
                BigDecimal.ZERO, new BigDecimal("1.00"));

        // min(1.00, 0.125) rounded half up would be 0.13 > 0.125; the cap rounds the amount down to 0.12.
        BigDecimal fee = p.fee(new BigDecimal("0.125"));
        assertEquals(new BigDecimal("0.12"), fee);
        assertTrue(fee.compareTo(new BigDecimal("0.125")) <= 0);
    }
}
