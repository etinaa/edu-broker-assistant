package ai.lab.broker.agent;

import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GroundedExecutorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<ToolCall> executed = new ArrayList<>();

    private GroundedExecutor executor(String seen, String resultContent) {
        return new GroundedExecutor(seen, call -> {
            executed.add(call);
            return ToolResult.ok(call, resultContent);
        });
    }

    private static ToolCall call(String name, String arguments) throws Exception {
        return new ToolCall("c1", name, JSON.readTree(arguments));
    }

    @Test
    void tickerTheUserTypedRunsInAnyCase() throws Exception {
        ToolResult result = executor("buy 2 trnc", "{}").apply(call("place_order", "{\"ticker\":\"TRNC\"}"));

        assertFalse(result.error());
        assertEquals(1, executed.size());
    }

    @Test
    void correctedOrMadeUpTickerIsRejectedWithoutRunning() throws Exception {
        ToolResult result = executor("buy 2 Training Corp common", "{}")
                .apply(call("place_order", "{\"ticker\":\"TRNC\"}"));

        assertTrue(result.error());
        assertTrue(result.content().contains("find_security"));
        assertTrue(executed.isEmpty());
    }

    @Test
    void partOfASeenTickerIsNotEnough() throws Exception {
        assertTrue(executor("price of TRNC", "{}").apply(call("get_quote", "{\"ticker\":\"TRN\"}")).error());
    }

    @Test
    void tickerFromAnEarlierResultOfTheSameMessageIsSeen() throws Exception {
        GroundedExecutor executor = executor("buy 2 Training Corp common", "{\"securities\":[{\"ticker\":\"TRNC\"}]}");

        executor.apply(call("find_security", "{\"query\":\"Training Corp common\"}"));
        ToolResult order = executor.apply(call("place_order", "{\"ticker\":\"TRNC\"}"));

        assertFalse(order.error());
    }

    @Test
    void callsWithoutTickerAreNotChecked() throws Exception {
        assertFalse(executor("", "{}").apply(call("get_account_summary", "{}")).error());
    }
}
