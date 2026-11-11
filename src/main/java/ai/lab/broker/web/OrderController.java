package ai.lab.broker.web;

import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.domain.Order;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/**
 * Подтверждение — единственный путь исполнить сделку. Его вызывает человек (кнопка, curl), а не модель:
 * у модели нет такого инструмента.
 */
@RestController
@RequestMapping("/orders")
class OrderController {

    private final BrokerService broker;

    OrderController(BrokerService broker) {
        this.broker = broker;
    }

    @GetMapping("/{id}")
    Order order(@PathVariable String id) {
        return broker.orderById(id).orElseThrow(() -> new NoSuchElementException("Заявка " + id + " не найдена"));
    }

    @PostMapping("/{id}/confirm")
    Order confirm(@PathVariable String id) {
        //return broker.confirmOrder(id);
        return null;
    }

    @PostMapping("/{id}/reject")
    Order reject(@PathVariable String id) {
        //return broker.reject(id);
        return null;
    }
}
