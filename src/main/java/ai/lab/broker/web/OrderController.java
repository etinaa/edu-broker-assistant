package ai.lab.broker.web;

import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.domain.Order;
import ai.lab.broker.domain.OrderDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
class OrderController {

    private final BrokerService broker;

    @GetMapping("/{id}")
    Order order(@RequestHeader(ChatController.ACCOUNT_HEADER) String accountId, @PathVariable String id) {
        return ownedOrder(accountId, id);
    }

    @PostMapping("/{id}/confirm")
    Order confirm(@RequestHeader(ChatController.ACCOUNT_HEADER) String accountId, @PathVariable String id) {
        return resultingOrder(broker.confirmOrder(accountId, id));
    }

    @PostMapping("/{id}/reject")
    Order reject(@RequestHeader(ChatController.ACCOUNT_HEADER) String accountId, @PathVariable String id) {
        return resultingOrder(broker.rejectOrder(accountId, id));
    }

    private Order ownedOrder(String accountId, String orderId) {
        return broker.orderById(orderId)
                .filter(o -> o.getAccountId().equals(accountId))
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));
    }

    /**
     * confirm and reject check ownership in BrokerService under its lock, in one lookup: an unknown or foreign order
     * is ORDER_NOT_FOUND → 404, any other refusal → 409.
     */
    private static Order resultingOrder(OrderDecision decision) {
        return switch (decision) {
            case OrderDecision.Confirmed c -> c.order();
            case OrderDecision.Rejected r -> r.order();
            case OrderDecision.Refused r when r.reason() == OrderDecision.Reason.ORDER_NOT_FOUND ->
                    throw new NoSuchElementException(r.message());
            case OrderDecision.Refused r -> throw new OrderRefusedException(r);
            // confirm and reject never create an order: reaching this is a bug, answered as 500.
            case OrderDecision.Created c -> throw new IllegalStateException("Unexpected decision: " + c);
        };
    }
}
