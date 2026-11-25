package ai.lab.broker.agent;

import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AmbiguityGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CANDIDATES = "{\"match\":\"CANDIDATES\",\"securities\":[]}";
    private static final String EXACT = "{\"match\":\"EXACT\",\"securities\":[]}";
    private static final String NOT_FOUND = "{\"match\":\"NOT_FOUND\",\"securities\":[]}";
    private static final String SUGGESTIONS =
            "{\"match\":\"NOT_FOUND\",\"securities\":[],\"suggestions\":[{\"ticker\":\"DEMO\"}]}";

    private final List<String> executed = new ArrayList<>();

    /**
     * find_security answers by query: "Training" → CANDIDATES, "TRNC" and "DEMO" → EXACT, "DEMA" → NOT_FOUND with
     * suggestions, anything else → NOT_FOUND.
     */
    private final AmbiguityGuard guard = new AmbiguityGuard(call -> {
        executed.add(call.name());
        String query = call.arguments().path("query").asText();
        Map<String, String> search = Map.of("Training", CANDIDATES, "TRNC", EXACT, "DEMA", SUGGESTIONS,
                "DEMO", EXACT);
        return ToolResult.ok(call, "find_security".equals(call.name()) ? search.getOrDefault(query, NOT_FOUND) : "{}");
    });

    private ToolResult apply(String name, String arguments) throws Exception {
        return guard.apply(new ToolCall("c1", name, JSON.readTree(arguments)));
    }

    @Test
    void afterCandidatesOtherToolsAreRefusedWithoutRunning() throws Exception {
        apply("find_security", "{\"query\":\"Training\"}");

        ToolResult summary = apply("get_account_summary", "{}");

        assertTrue(summary.error());
        assertEquals(AmbiguityGuard.ASK_FIRST, summary.content());
        assertEquals(List.of("find_security"), executed);
    }

    @Test
    void aNarrowerSearchWithExactMatchLiftsTheBlockForReadingTools() throws Exception {
        apply("find_security", "{\"query\":\"Training\"}");
        apply("find_security", "{\"query\":\"TRNC\"}");

        assertFalse(apply("get_quote", "{\"ticker\":\"TRNC\"}").error());
    }

    @Test
    void afterCandidatesNoOrderInThisMessageEvenIfTheModelNarrowsTheSearchItself() throws Exception {
        apply("find_security", "{\"query\":\"Training\"}");
        apply("find_security", "{\"query\":\"TRNC\"}");

        ToolResult order = apply("place_order", "{\"ticker\":\"TRNC\"}");

        assertTrue(order.error());
        assertEquals(AmbiguityGuard.NO_ORDER_ON_A_CANDIDATE, order.content());
        assertFalse(executed.contains("place_order"));
    }

    @Test
    void anExactSearchWithoutEarlierCandidatesAllowsAnOrder() throws Exception {
        apply("find_security", "{\"query\":\"TRNC\"}");

        assertFalse(apply("place_order", "{\"ticker\":\"TRNC\"}").error());
    }

    @Test
    void notFoundDoesNotLiftTheBlock() throws Exception {
        apply("find_security", "{\"query\":\"Training\"}");
        apply("find_security", "{\"query\":\"Trainnig\"}");

        assertTrue(apply("get_quote", "{\"ticker\":\"TRNC\"}").error());
    }

    @Test
    void afterSuggestionsOtherToolsAreRefusedWithoutRunning() throws Exception {
        apply("find_security", "{\"query\":\"DEMA\"}");

        ToolResult order = apply("place_order", "{\"ticker\":\"DEMO\"}");

        assertTrue(order.error());
        assertEquals(AmbiguityGuard.CONFIRM_SUGGESTION_FIRST, order.content());
        assertEquals(List.of("find_security"), executed);
    }

    @Test
    void anExactSearchAfterSuggestionsLiftsTheBlock() throws Exception {
        apply("find_security", "{\"query\":\"DEMA\"}");
        apply("find_security", "{\"query\":\"DEMO\"}");

        assertFalse(apply("get_quote", "{\"ticker\":\"DEMO\"}").error());
    }

    @Test
    void afterSuggestionsNoOrderInThisMessageEvenIfTheModelFindsTheSuggestedTickerItself() throws Exception {
        apply("find_security", "{\"query\":\"DEMA\"}");
        apply("find_security", "{\"query\":\"DEMO\"}");

        ToolResult order = apply("place_order", "{\"ticker\":\"DEMO\"}");

        assertTrue(order.error());
        assertEquals(AmbiguityGuard.NO_ORDER_ON_A_SUGGESTION, order.content());
        assertFalse(executed.contains("place_order"));
    }

    @Test
    void notFoundWithoutSuggestionsBlocksNothing() throws Exception {
        apply("find_security", "{\"query\":\"Tesla\"}");

        assertFalse(apply("get_account_summary", "{}").error());
    }

    @Test
    void withoutCandidatesNothingIsBlocked() throws Exception {
        assertFalse(apply("get_account_summary", "{}").error());
        apply("find_security", "{\"query\":\"TRNC\"}");
        assertFalse(apply("get_quote", "{\"ticker\":\"TRNC\"}").error());
    }
}
