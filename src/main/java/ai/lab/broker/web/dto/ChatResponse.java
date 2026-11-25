package ai.lab.broker.web.dto;

import ai.lab.broker.domain.Order;
import ai.lab.broker.domain.OrderDecision;
import ai.lab.broker.domain.OrderSide;
import ai.lab.broker.domain.OrderStatus;
import ai.lab.broker.domain.Security;

import java.math.BigDecimal;
import java.util.List;

/**
 * The /chat response. answer is the model's text for the user; code builds pendingOrder, candidates and
 * suggestions from the tool results. If the model's text disagrees with the numbers here, the structure is right.
 * pendingOrder is null if this message created no order. candidates are securities that matched, one of which the
 * user must choose; suggestions are securities that did not match but look like what the user typed ("did you
 * mean"). Both are empty if there is nothing to choose from, and at most one of them is not empty.
 */
public record ChatResponse(String answer, PendingOrder pendingOrder, List<Security> candidates,
                           List<Security> suggestions, boolean limitReached, String traceId) {

    public record PendingOrder(String orderId, OrderSide side, String ticker, long quantity,
                               BigDecimal price, BigDecimal fee, BigDecimal total, BigDecimal cashAfter,
                               OrderStatus status) {

        public static PendingOrder from(OrderDecision.Created created) {
            return from(created.order(), created.cashAfter());
        }

        public static PendingOrder from(Order order, BigDecimal cashAfter) {
            return new PendingOrder(order.getId(), order.getSide(), order.getTicker(), order.getQuantity(),
                    order.getPrice(), order.getFee(), order.total(), cashAfter, order.getStatus());
        }
    }
}
