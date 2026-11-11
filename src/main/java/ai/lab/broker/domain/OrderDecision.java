package ai.lab.broker.domain;

import java.math.BigDecimal;

/**
 * Итог попытки создать заявку: либо заявка создана и ждёт подтверждения, либо отказ с причиной.
 * Отказ — обычный бизнес-результат, а не исключение: его модель пересказывает пользователю.
 */
public sealed interface OrderDecision {

    /** cashAfter — сколько денег останется на счёте, если заявку подтвердят. */
    record Created(Order order, BigDecimal cashAfter) implements OrderDecision {}

    /** message — человекочитаемая причина с цифрами, например «нужно 12 300.00 USD, на счёте 10 000.00 USD». */
    record Refused(Reason reason, String message) implements OrderDecision {}

    record Confirmed(Order order) implements OrderDecision {}

    record Rejected(Order order) implements OrderDecision {}

    enum Reason { ILLEGAL_ACCESS, INSUFFICIENT_FUNDS, INSUFFICIENT_POSITION, INVALID_QUANTITY, UNKNOWN_SECURITY }
}
