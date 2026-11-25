package ai.lab.broker.broker;

import ai.lab.broker.config.BrokerProperties;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;

/** BrokerService on the demo data with the rules from application.yml: orders live 5 minutes, price move 2%. */
public final class TestBroker {

    public static final BrokerProperties PROPERTIES = new BrokerProperties(Duration.ofMinutes(5), new BigDecimal("2"));

    private TestBroker() {
    }

    public static BrokerService demo() {
        return with(new SecurityCatalog(), new OrderBook(), Clock.systemUTC());
    }

    public static BrokerService with(SecurityCatalog catalog, OrderBook orders, Clock clock) {
        return new BrokerService(catalog, new AccountRepository(), orders, PROPERTIES, clock);
    }
}
