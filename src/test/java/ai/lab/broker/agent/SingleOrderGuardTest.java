package ai.lab.broker.agent;

import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SingleOrderGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATED = "{\"result\":\"CREATED\",\"orderId\":\"ORD-1\"}";
    private static final String REFUSED = "{\"result\":\"REFUSED\",\"reason\":\"INSUFFICIENT_FUNDS\"}";

    private final List<String> executed = new ArrayList<>();
    private final Deque<String> placeOrderResults = new ArrayDeque<>();

    private final SingleOrderGuard guard = new SingleOrderGuard(call -> {
        executed.add(call.name());
        return ToolResult.ok(call, "place_order".equals(call.name()) ? placeOrderResults.removeFirst() : "{}");
    });

    private ToolResult apply(String name) throws Exception {
        return guard.apply(new ToolCall("c1", name, JSON.readTree("{}")));
    }

    @Test
    void secondOrderOfTheMessageIsRefusedWithoutRunning() throws Exception {
        placeOrderResults.add(CREATED);

        assertFalse(apply("place_order").error());
        ToolResult second = apply("place_order");

        assertTrue(second.error());
        assertEquals(SingleOrderGuard.ONE_ORDER.formatted("ORD-1", "ORD-1"), second.content());
        assertEquals(List.of("place_order"), executed);
    }

    @Test
    void refusedOrderDoesNotCountSoTheModelMayTryAgain() throws Exception {
        placeOrderResults.add(REFUSED);
        placeOrderResults.add(CREATED);

        apply("place_order");
        assertFalse(apply("place_order").error());

        assertEquals(List.of("place_order", "place_order"), executed);
    }

    @Test
    void otherToolsStillRunAfterAnOrder() throws Exception {
        placeOrderResults.add(CREATED);
        apply("place_order");

        assertFalse(apply("get_quote").error());
        assertEquals(List.of("place_order", "get_quote"), executed);
    }
}
