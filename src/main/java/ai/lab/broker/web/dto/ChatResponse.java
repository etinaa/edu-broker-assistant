package ai.lab.broker.web.dto;

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

        /*public static PendingOrder from(OrderDecision.Created created) {
            var o = created.order();
            return new PendingOrder(o.id(), o.side(), o.ticker(), o.quantity(), o.price(), o.total(),
                    created.cashAfter(), o.status());
        }*/
    }
}
