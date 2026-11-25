package ai.lab.broker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * Order rules from the {@code broker} section of {@code application.yml} (task 6).
 * <ul>
 *   <li>orderTtl — how long an order waits for confirmation; after that it is EXPIRED;</li>
 *   <li>maxPriceMovePercent — how far the price may move against the client between placing and confirming
 *       (up for BUY, down for SELL) before the order fails with PRICE_CHANGED; a move in the client's favour
 *       is always allowed;</li>
 *   <li>maxOrders — how many orders are kept in memory; above it the oldest finished or expired orders are
 *       removed (a removed order is then not found). Pending orders are never removed: they expire after orderTtl;</li>
 *   <li>feePercent and minFee — the broker fee (task 10): feePercent of price × quantity, at least minFee, in USD
 *       rounded to cents. Not set means 0, so by default there is no fee. See {@link #fee(BigDecimal)}.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "broker")
public record BrokerProperties(Duration orderTtl, BigDecimal maxPriceMovePercent, int maxOrders,
                               BigDecimal feePercent, BigDecimal minFee) {

    public BrokerProperties {
        feePercent = feePercent == null ? BigDecimal.ZERO : feePercent;
        minFee = minFee == null ? BigDecimal.ZERO : minFee;
        if (feePercent.signum() < 0) {
            throw new IllegalArgumentException("broker.fee-percent must be zero or positive: " + feePercent);
        }
        if (minFee.signum() < 0) {
            throw new IllegalArgumentException("broker.min-fee must be zero or positive: " + minFee);
        }
        if (orderTtl == null || orderTtl.isNegative() || orderTtl.isZero()) {
            throw new IllegalArgumentException("broker.order-ttl must be positive: " + orderTtl);
        }
        if (maxPriceMovePercent == null || maxPriceMovePercent.signum() < 0) {
            throw new IllegalArgumentException(
                    "broker.max-price-move-percent must be zero or positive: " + maxPriceMovePercent);
        }
        if (maxOrders <= 0) {
            throw new IllegalArgumentException("broker.max-orders must be positive: " + maxOrders);
        }
    }

    /**
     * The fee for a trade of this amount (price × quantity): feePercent of it rounded half up to cents, at least
     * minFee, but never more than the amount itself (rounded down to cents), so a sale never costs the client money.
     * The cap is applied after rounding: rounding a capped fee up could take it above the amount again.
     */
    public BigDecimal fee(BigDecimal amount) {
        BigDecimal byPercent = amount.multiply(feePercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal fee = byPercent.max(minFee).setScale(2, RoundingMode.HALF_UP);
        return fee.min(amount.setScale(2, RoundingMode.DOWN));
    }
}
