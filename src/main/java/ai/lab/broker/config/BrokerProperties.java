package ai.lab.broker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Order rules from the {@code broker} section of {@code application.yml} (task 6).
 * <ul>
 *   <li>orderTtl — how long an order waits for confirmation; after that it is EXPIRED;</li>
 *   <li>maxPriceMovePercent — how far the price may move against the client between placing and confirming
 *       (up for BUY, down for SELL) before the order fails with PRICE_CHANGED; a move in the client's favour
 *       is always allowed.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "broker")
public record BrokerProperties(Duration orderTtl, BigDecimal maxPriceMovePercent) {

    public BrokerProperties {
        if (orderTtl == null || orderTtl.isNegative() || orderTtl.isZero()) {
            throw new IllegalArgumentException("broker.order-ttl must be positive: " + orderTtl);
        }
        if (maxPriceMovePercent == null || maxPriceMovePercent.signum() < 0) {
            throw new IllegalArgumentException(
                    "broker.max-price-move-percent must be zero or positive: " + maxPriceMovePercent);
        }
    }
}
