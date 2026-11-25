package ai.lab.broker.broker;

import ai.lab.broker.config.BrokerProperties;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;

/** BrokerService on the demo data with the rules from application.yml: orders live 5 minutes, price move 2%, no fee. */
public final class TestBroker {

    public static final BrokerProperties PROPERTIES = new BrokerProperties(Duration.ofMinutes(5), new BigDecimal("2"), 10_000,
            BigDecimal.ZERO, BigDecimal.ZERO);

    private TestBroker() {
    }

    public static BrokerService demo() {
        return with(new SecurityCatalog(), new OrderBook(), Clock.systemUTC());
    }

    public static BrokerService with(SecurityCatalog catalog, OrderBook orders, Clock clock) {
        return with(catalog, orders, clock, PROPERTIES);
    }

    public static BrokerService with(SecurityCatalog catalog, OrderBook orders, Clock clock,
                                     BrokerProperties properties) {
        return new BrokerService(catalog, new AccountRepository(), orders, properties, clock);
    }
}
