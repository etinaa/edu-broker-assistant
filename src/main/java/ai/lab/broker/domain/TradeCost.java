package ai.lab.broker.domain;

import java.math.BigDecimal;
import java.util.function.UnaryOperator;

/**
 * What a trade costs: amount = price × quantity, the broker fee, and the total the account pays (BUY: amount + fee)
 * or receives (SELL: amount − fee). The only place where the total is computed: the funds check, cashAfter, the
 * account change on confirm and {@link Order#total()} all use it.
 */
public record TradeCost(OrderSide side, BigDecimal amount, BigDecimal fee) {

    /** feeRule turns the amount into the fee (BrokerProperties::fee). */
    public static TradeCost of(OrderSide side, long quantity, BigDecimal price, UnaryOperator<BigDecimal> feeRule) {
        BigDecimal amount = amount(quantity, price);
        return new TradeCost(side, amount, feeRule.apply(amount));
    }

    /** With a fee already known, e.g. the one stored in an order. */
    public static TradeCost withFee(OrderSide side, long quantity, BigDecimal price, BigDecimal fee) {
        return new TradeCost(side, amount(quantity, price), fee);
    }

    private static BigDecimal amount(long quantity, BigDecimal price) {
        return price.multiply(BigDecimal.valueOf(quantity));
    }

    public BigDecimal total() {
        return side == OrderSide.BUY ? amount.add(fee) : amount.subtract(fee);
    }
}
