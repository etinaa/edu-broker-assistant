package ai.lab.broker.web.dto;

import ai.lab.broker.domain.Order;
import ai.lab.broker.domain.OrderDecision;
import ai.lab.broker.domain.OrderSide;
import ai.lab.broker.domain.OrderStatus;
import ai.lab.broker.domain.Security;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ответ /chat. answer — текст модели для человека; pendingOrder и candidates собирает код из результатов
 * инструментов. Если текст модели разошёлся с цифрами здесь — права структура.
 * pendingOrder — null, если заявка в этом сообщении не создавалась; candidates — пустой, если выбирать не из чего.
 */
public record ChatResponse(String answer, PendingOrder pendingOrder, List<Security> candidates,
                           boolean limitReached, String traceId) {

    public record PendingOrder(String orderId, OrderSide side, String ticker, long quantity,
                               BigDecimal price, BigDecimal total, BigDecimal cashAfter, OrderStatus status) {

        public static PendingOrder from(OrderDecision.Created created) {
            return from(created.order(), created.cashAfter());
        }

        public static PendingOrder from(Order order, BigDecimal cashAfter) {
            return new PendingOrder(order.getId(), order.getSide(), order.getTicker(), order.getQuantity(),
                    order.getPrice(), order.total(), cashAfter, order.getStatus());
        }
    }
}
