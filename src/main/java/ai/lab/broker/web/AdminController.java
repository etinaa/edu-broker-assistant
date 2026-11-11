package ai.lab.broker.web;

import ai.lab.broker.agent.SessionStore;
import ai.lab.broker.agent.TraceStore;
import ai.lab.broker.broker.AccountRepository;
import ai.lab.broker.broker.OrderBook;
import ai.lab.broker.broker.SecurityCatalog;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Сброс к исходным учебным данным между прогонами сценариев. Только для учебного стенда. */
@RestController
class AdminController {

    private final SecurityCatalog catalog;
    private final AccountRepository accounts;
    private final OrderBook orders;
    private final SessionStore sessions;
    private final TraceStore traces;

    AdminController(SecurityCatalog catalog, AccountRepository accounts, OrderBook orders,
                    SessionStore sessions, TraceStore traces) {
        this.catalog = catalog;
        this.accounts = accounts;
        this.orders = orders;
        this.sessions = sessions;
        this.traces = traces;
    }

    @PostMapping("/admin/reset")
    ResponseEntity<Void> reset() {
        //catalog.reset();
        //accounts.reset();
        //orders.reset();
        sessions.clear();
        traces.clear();
        return ResponseEntity.noContent().build();
    }
}
