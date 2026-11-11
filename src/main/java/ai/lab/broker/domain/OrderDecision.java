package ai.lab.broker.domain;

import java.math.BigDecimal;

/**
 * Итог попытки создать заявку: либо заявка создана и ждёт подтверждения, либо отказ с причиной.
 * Отказ — обычный бизнес-результат, а не исключение: его модель пересказывает пользователю.
 */
public sealed interface OrderDecision {

    /** cashAfter — сколько денег останется на счёте, если заявку подтвердят. */
    record Created(Order order, BigDecimal cashAfter) implements OrderDecision {}

    /**
     * message — человекочитаемая причина с цифрами на английском. Для нехватки денег или бумаг цифры дублируются
     * структурно: needed (нужно), available (есть), shortfall (не хватает = needed − available); для остальных
     * отказов они null. Числа в ответ модели берутся отсюда, а не из текста message.
     */
    record Refused(Reason reason, String message, BigDecimal needed, BigDecimal available, BigDecimal shortfall)
            implements OrderDecision {

        /** Отказ без цифр. Снаружи отказы создаются фабриками ниже: тексты причин живут только здесь. */
        public Refused(Reason reason, String message) {
            this(reason, message, null, null, null);
        }

        public static Refused accountNotFound(String accountId) {
            return new Refused(Reason.ILLEGAL_ACCESS, "Account with accountId = " + accountId + " not found");
        }

        public static Refused orderNotFound(String orderId) {
            return new Refused(Reason.ILLEGAL_ACCESS, "Order with orderId = " + orderId + " not found");
        }

        public static Refused orderNotOwned(String orderId, String accountId) {
            return new Refused(Reason.ILLEGAL_ACCESS,
                    "OrderId = " + orderId + " not related to accountId = " + accountId);
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

        public static Refused insufficientPosition(long needed, long available) {
            long shortfall = needed - available;
            return new Refused(Reason.INSUFFICIENT_POSITION,
                    "Insufficient securities: needed " + needed + ", available " + available
                            + ", short by " + shortfall,
                    BigDecimal.valueOf(needed), BigDecimal.valueOf(available), BigDecimal.valueOf(shortfall));
        }
    }

    record Confirmed(Order order) implements OrderDecision {}

    record Rejected(Order order) implements OrderDecision {}

    enum Reason { ILLEGAL_ACCESS, INSUFFICIENT_FUNDS, INSUFFICIENT_POSITION, INVALID_QUANTITY, UNKNOWN_SECURITY }
}
