package ai.lab.broker.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
public class Order {

    private final String id;
    private final Instant createdAt;
    private final String accountId;
    private final OrderSide side;
    private final String ticker;
    private final long quantity;
    private final BigDecimal price;
    private OrderStatus status;
    private String reason;

    public Order(String id, String accountId, OrderSide side, String ticker, long quantity, BigDecimal price) {
        this.id = id;
        this.createdAt = Instant.now();
        this.accountId = accountId;
        this.side = side;
        this.ticker = ticker;
        this.quantity = quantity;
        this.price = price;
        this.status = OrderStatus.PENDING_CONFIRMATION;
        this.reason = "";
    }

    public BigDecimal total() {
        return price.multiply(new BigDecimal(quantity));
    }

    public void setStatus(OrderStatus status, String reason) {
        this.status = status;
        this.reason = reason;
    }
}
