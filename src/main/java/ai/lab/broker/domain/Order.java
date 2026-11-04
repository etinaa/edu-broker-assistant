package ai.lab.broker.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Заявка на покупку или продажу. price — цена на момент создания, total = price × quantity.
 * failureReason заполняется для FAILED.
 */
public record Order(String id, String accountId, OrderSide side, String ticker, long quantity,
                    BigDecimal price, BigDecimal total, OrderStatus status, Instant createdAt,
                    String failureReason) {

    public Order withStatus(OrderStatus newStatus) {
        return new Order(id, accountId, side, ticker, quantity, price, total, newStatus, createdAt, failureReason);
    }

    public Order failed(String reason) {
        return new Order(id, accountId, side, ticker, quantity, price, total, OrderStatus.FAILED, createdAt, reason);
    }
}
