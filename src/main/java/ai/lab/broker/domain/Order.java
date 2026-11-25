package ai.lab.broker.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Order to buy or sell a security, priced when it is created: price and fee are the quote at that moment, and
 * {@link #total()} is what the client would pay (BUY) or receive (SELL) at it. Confirm executes at the current
 * price with the fee recomputed; executedPrice and executedFee record what was actually charged (null until
 * EXECUTED). Starts as PENDING_CONFIRMATION; reason explains the latest status change. Not synchronized by itself:
 * BrokerService changes it under its lock and gives callers {@link #snapshot() snapshots}.
 */
@Getter
public class Order {

    private final String id;
    private final Instant createdAt;
    private final String accountId;
    private final OrderSide side;
    private final String ticker;
    private final long quantity;
    private final BigDecimal price;
    private final BigDecimal fee;
    private OrderStatus status;
    private String reason;
    private BigDecimal executedPrice;
    private BigDecimal executedFee;

    public Order(String id, String accountId, OrderSide side, String ticker, long quantity, BigDecimal price,
                 BigDecimal fee, Instant createdAt) {
        this.id = id;
        this.createdAt = createdAt;
        this.accountId = accountId;
        this.side = side;
        this.ticker = ticker;
        this.quantity = quantity;
        this.price = price;
        this.fee = fee;
        this.status = OrderStatus.PENDING_CONFIRMATION;
        this.reason = "";
    }

    private Order(Order source) {
        this(source.id, source.accountId, source.side, source.ticker, source.quantity, source.price, source.fee,
                source.createdAt);
        this.status = source.status;
        this.reason = source.reason;
        this.executedPrice = source.executedPrice;
        this.executedFee = source.executedFee;
    }

    /**
     * A copy with the current status, reason and execution. BrokerService hands out copies taken under its lock,
     * so a reader (JSON serialization, the chat response) never sees a status together with the reason of another one.
     */
    public Order snapshot() {
        return new Order(this);
    }

    private TradeCost quotedCost() {
        return TradeCost.withFee(side, quantity, price, fee);
    }

    /** price × quantity at the quote, without the fee. */
    @JsonProperty
    public BigDecimal amount() {
        return quotedCost().amount();
    }

    /** At the quote: what the client pays for a BUY (amount + fee) or receives for a SELL (amount − fee). */
    @JsonProperty
    public BigDecimal total() {
        return quotedCost().total();
    }

    /** What was actually paid or received on execution; null until EXECUTED. */
    @JsonProperty
    public BigDecimal executedTotal() {
        return executedPrice == null ? null : TradeCost.withFee(side, quantity, executedPrice, executedFee).total();
    }

    public void setStatus(OrderStatus status, String reason) {
        this.status = status;
        this.reason = reason;
    }

    /** EXECUTED at the price and fee the account was actually charged with. */
    public void execute(BigDecimal executedPrice, BigDecimal executedFee, String reason) {
        this.executedPrice = executedPrice;
        this.executedFee = executedFee;
        setStatus(OrderStatus.EXECUTED, reason);
    }
}
