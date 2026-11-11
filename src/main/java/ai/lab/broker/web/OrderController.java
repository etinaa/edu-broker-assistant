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

    static final String ACCOUNT_HEADER = "X-Account-Id";

    private final BrokerService broker;

    @GetMapping("/{id}")
    Order order(@RequestHeader(ACCOUNT_HEADER) String accountId, @PathVariable String id) {
        return ownedOrder(accountId, id);
    }

    @PostMapping("/{id}/confirm")
    Order confirm(@RequestHeader(ACCOUNT_HEADER) String accountId, @PathVariable String id) {
        ownedOrder(accountId, id);
        return resultingOrder(broker.confirmOrder(accountId, id));
    }

    @PostMapping("/{id}/reject")
    Order reject(@RequestHeader(ACCOUNT_HEADER) String accountId, @PathVariable String id) {
        ownedOrder(accountId, id);
        return resultingOrder(broker.rejectOrder(accountId, id));
    }

    private Order ownedOrder(String accountId, String orderId) {
        return broker.orderById(orderId)
                .filter(o -> o.getAccountId().equals(accountId))
                .orElseThrow(() -> new NoSuchElementException("Order " + orderId + " not found"));
    }

    private static Order resultingOrder(OrderDecision decision) {
        return switch (decision) {
            case OrderDecision.Confirmed c -> c.order();
            case OrderDecision.Rejected r -> r.order();
            case OrderDecision.Refused r -> throw new IllegalStateException(r.message());
            case OrderDecision.Created c -> throw new IllegalStateException("Unexpected decision: " + c);
        };
    }
}
