package ai.lab.broker.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadsTest {

    @Test
    void shortErrorBodyIsKeptAsIs() {
        assertEquals("{\"error\":\"model not found\"}", Payloads.errorBody("{\"error\":\"model not found\"}"));
        assertNull(Payloads.errorBody(null));
    }

    @Test
    void longErrorBodyIsCutWithItsLength() {
        String body = "x".repeat(500) + "SECRET";

        String cut = Payloads.errorBody(body);

        assertFalse(cut.contains("SECRET"));
        assertTrue(cut.endsWith("... (506 chars)"), cut);
    }

    @Test
    void payloadLoggerIsOutsideTheApplicationHierarchy() {
        assertFalse(Payloads.LOG.getName().startsWith("ai.lab.broker"));
    }
}
