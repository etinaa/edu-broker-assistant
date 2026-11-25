package ai.lab.broker.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class BrokerPropertiesTest {

    @Test
    void nonPositiveLifetimeOrNegativePriceMoveIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BrokerProperties(Duration.ZERO, BigDecimal.ONE, 100));
        assertThrows(IllegalArgumentException.class, () -> new BrokerProperties(null, BigDecimal.ONE, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new BrokerProperties(Duration.ofMinutes(5), new BigDecimal("-1"), 100));
    }

    @Test
    void nonPositiveMaxOrdersIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new BrokerProperties(Duration.ofMinutes(5), BigDecimal.ONE, 0));
    }

    @Test
    void zeroPriceMoveIsAllowed() {
        assertDoesNotThrow(() -> new BrokerProperties(Duration.ofMinutes(5), BigDecimal.ZERO, 100));
    }
}
