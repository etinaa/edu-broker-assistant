package ai.lab.broker.web;

import ai.lab.broker.agent.SessionStore;
import ai.lab.broker.agent.TraceStore;
import ai.lab.broker.broker.AccountRepository;
import ai.lab.broker.broker.OrderBook;

import org.springframework.context.ApplicationContext;

/**
 * Puts the in-memory state of a shared Spring context back to startup: demo accounts, no orders (ids from ORD-1),
 * no sessions or traces. Faster than recreating the context with {@code @DirtiesContext} for every test.
 */
final class DemoState {

    private DemoState() {
    }

    static void reset(ApplicationContext context) {
        context.getBean(AccountRepository.class).reset();
        context.getBean(OrderBook.class).clear();
        context.getBean(SessionStore.class).clear();
        context.getBean(TraceStore.class).clear();
    }
}
