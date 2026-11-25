package ai.lab.broker.domain;

import java.math.BigDecimal;

/**
 * Outcome of an order operation: Created (waits for confirmation), Confirmed, Rejected, or Refused with a reason.
 * A refusal is an ordinary business result, not an exception: the model relays it to the user.
 */
public sealed interface OrderDecision {

    /** cashAfter — the cash left on the account if the order is confirmed at its current price. */
    record Created(Order order, BigDecimal cashAfter) implements OrderDecision {}

    /**
     * message — a human-readable reason in English, with numbers. For insufficient cash or securities the numbers
     * are also given as fields: needed, available and shortfall (= needed − available); for other refusals they
     * are null. Numbers in the model's answer come from these fields, not from the message text.
     */
    record Refused(Reason reason, String message, BigDecimal needed, BigDecimal available, BigDecimal shortfall)
            implements OrderDecision {

        /** A refusal without numbers. Callers use the factories below, so all reason texts live in this record. */
        public Refused(Reason reason, String message) {
            this(reason, message, null, null, null);
        }

        public static Refused accountNotFound(String accountId) {
            return new Refused(Reason.ILLEGAL_ACCESS, "Account with accountId = " + accountId + " not found");
        }

        /** Also for another account's order: the same text, so order ids of other accounts cannot be probed. */
        public static Refused orderNotFound(String orderId) {
            return new Refused(Reason.ORDER_NOT_FOUND, "Order " + orderId + " not found");
        }

        public static Refused cannotConfirm(OrderStatus status) {
            return new Refused(Reason.ILLEGAL_ACCESS, "Can't confirm order with status = " + status);
        }

        public static Refused cannotReject(OrderStatus status) {
            return new Refused(Reason.ILLEGAL_ACCESS, "Can't reject order with status = " + status);
        }

        public static Refused invalidQuantity() {
            return new Refused(Reason.INVALID_QUANTITY, "Quantity must be greater than 0");
        }

        public static Refused nonIntegerQuantity(String quantity) {
            return new Refused(Reason.INVALID_QUANTITY,
                    "Quantity must be a whole positive number of shares, got " + quantity);
        }

        public static Refused unknownSecurity(String ticker) {
            return new Refused(Reason.UNKNOWN_SECURITY, "Security with ticker = " + ticker + " not found");
        }

        public static Refused insufficientFunds(BigDecimal needed, BigDecimal available) {
            BigDecimal shortfall = needed.subtract(available);
            return new Refused(Reason.INSUFFICIENT_FUNDS,
                    "Insufficient funds: needed " + needed.toPlainString() + " USD, available "
                            + available.toPlainString() + " USD, short by " + shortfall.toPlainString() + " USD",
                    needed, available, shortfall);
        }

        /** The price moved against the client by more than allowed; numbers: order price, current price. */
        public static Refused priceChanged(String ticker, BigDecimal orderPrice, BigDecimal currentPrice,
                                           BigDecimal maxMovePercent) {
            return new Refused(Reason.PRICE_CHANGED,
                    "Price of " + ticker + " moved from " + orderPrice.toPlainString() + " to "
                            + currentPrice.toPlainString() + " USD, more than the allowed "
                            + maxMovePercent.stripTrailingZeros().toPlainString() + "%; place a new order");
        }

        public static Refused orderExpired(String orderId) {
            return new Refused(Reason.ORDER_EXPIRED,
                    "Order " + orderId + " expired before it was confirmed; place a new order");
        }

        public static Refused insufficientPosition(long needed, long available) {
            long shortfall = needed - available;
            return new Refused(Reason.INSUFFICIENT_POSITION,
                    "Insufficient securities: needed " + needed + ", available " + available
                            + ", short by " + shortfall,
                    BigDecimal.valueOf(needed), BigDecimal.valueOf(available), BigDecimal.valueOf(shortfall));
        }
    }

    /** The order passed the re-check and is EXECUTED. */
    record Confirmed(Order order) implements OrderDecision {}

    /** The user rejected the order: REJECTED_BY_USER. */
    record Rejected(Order order) implements OrderDecision {}

    /** Machine-readable refusal category. */
    enum Reason {
        ILLEGAL_ACCESS, ORDER_NOT_FOUND, INSUFFICIENT_FUNDS, INSUFFICIENT_POSITION, INVALID_QUANTITY, UNKNOWN_SECURITY,
        PRICE_CHANGED, ORDER_EXPIRED
    }
}
