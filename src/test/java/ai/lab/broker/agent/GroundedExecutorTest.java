package ai.lab.broker.agent;

import ai.lab.broker.llm.ToolConversation.Seen;
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

    /** userMessage — what the user typed earlier in the session; resultContent — what the tool will return. */
    private GroundedExecutor executor(String userMessage, String resultContent) {
        return executor(new Seen(List.of(userMessage), List.of()), resultContent);
    }

    private GroundedExecutor executor(Seen seen, String resultContent) {
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
    void tickerIsCheckedWithoutSurroundingSpacesAsTheToolWouldUseIt() throws Exception {
        Seen seen = new Seen(List.of(), List.of("{\"securities\":[{\"ticker\":\"TRNC\"}]}"));

        assertFalse(executor(seen, "{}").apply(call("get_quote", "{\"ticker\":\" TRNC \"}")).error());
        assertTrue(executor(seen, "{}").apply(call("get_quote", "{\"ticker\":\" TRN \"}")).error());
    }

    @Test
    void callWithoutArgumentsIsPassedToTheToolThatReportsTheSchemaError() {
        ToolResult result = executor("", "missing ticker")
                .apply(new ToolCall("c1", "get_quote", null));

        assertEquals("missing ticker", result.content());
        assertEquals(1, executed.size());
    }

    @Test
    void tickerFromAnEarlierMessagesResultIsSeen() throws Exception {
        Seen seen = new Seen(List.of("buy 100 Sample", "Logistics"),
                List.of("{\"match\":\"CANDIDATES\",\"securities\":[{\"ticker\":\"SMPB\"},{\"ticker\":\"SMPL\"}]}"));

        assertFalse(executor(seen, "{}").apply(call("place_order", "{\"ticker\":\"SMPL\"}")).error());
    }

    @Test
    void jsonKeysAndConstantsOfResultsAreNotIdentifiers() throws Exception {
        Seen seen = new Seen(List.of("how much is Sample Logistics?"),
                List.of("{\"match\":\"EXACT\",\"securities\":[{\"ticker\":\"SMPL\"}]}",
                        "{\"ticker\":\"SMPL\",\"price\":12.30,\"currency\":\"USD\"}"));

        for (String notATicker : List.of("USD", "EXACT", "ticker", "price")) {
            assertTrue(executor(seen, "{}").apply(call("get_quote", "{\"ticker\":\"" + notATicker + "\"}")).error(),
                    notATicker);
        }
    }

    @Test
    void tickerRepeatedInARefusalMessageIsNotSeen() throws Exception {
        GroundedExecutor executor = executor("buy 2 trnx", "{\"result\":\"REFUSED\",\"message\":\"Security TRNY not found\"}");

        executor.apply(call("place_order", "{\"ticker\":\"TRNX\"}"));

        assertTrue(executor.apply(call("place_order", "{\"ticker\":\"TRNY\"}")).error());
    }

    @Test
    void resultThatIsNotJsonAddsNothing() throws Exception {
        GroundedExecutor executor = executor("hi", "TRNC is a nice stock");

        executor.apply(call("get_account_summary", "{}"));

        assertTrue(executor.apply(call("get_quote", "{\"ticker\":\"TRNC\"}")).error());
    }

    @Test
    void tickerSurroundedByPunctuationInTheUsersTextIsSeen() throws Exception {
        assertFalse(executor("купи 2 акции (trnc), пожалуйста.", "{}")
                .apply(call("place_order", "{\"ticker\":\"TRNC\"}")).error());
    }

    @Test
    void valueThatIsNotASingleTokenIsNeverSeen() throws Exception {
        assertTrue(executor("buy TRNC common", "{}")
                .apply(call("place_order", "{\"ticker\":\"TRNC common\"}")).error());
    }

    @Test
    void callsWithoutTickerAreNotChecked() throws Exception {
        assertFalse(executor("", "{}").apply(call("get_account_summary", "{}")).error());
    }
}
