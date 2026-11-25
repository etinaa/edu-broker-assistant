package ai.lab.broker.agent;

import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.Function;

/**
 * Question 4: after find_security returned CANDIDATES, the model must ask the user, not pick a security itself.
 * The prompt and the tool description say so, but a local model still answered about one of the candidates. So
 * code enforces it: until a later find_security in the same message narrows the search down to EXACT, every other
 * tool call is refused, and the model has nothing to answer with but the question to the user.
 * <p>
 * The same holds for "did you mean" (task 10): NOT_FOUND with suggestions means the user typed something else, and
 * acting on a suggestion ("DEMA" → an order for DEMO) would be a guess. A NOT_FOUND without suggestions keeps
 * whatever state there was.
 * <p>
 * In both cases a later EXACT lifts the block for reading tools only, not for place_order: the model can search for
 * a candidate or a suggestion itself ("Training" → "TRNC"), and then it has chosen for the user. Only the user's
 * next message can confirm the choice.
 * <p>
 * One instance per message: the user's next message ("Logistics", "yes, DEMO") starts with a clean state.
 */
final class AmbiguityGuard implements Function<ToolCall, ToolResult> {

    static final String ASK_FIRST = "Several securities matched the last find_security. Do not choose one and do "
            + "not answer about any of them: list their tickers and names and ask the user which one they mean.";

    static final String CONFIRM_SUGGESTION_FIRST = "Nothing matched the last find_security; the suggestions only "
            + "look similar. Do not choose one, do not place an order and do not answer about any of them: list "
            + "their tickers and names and ask the user whether they meant one of them.";

    static final String NO_ORDER_ON_A_CANDIDATE = "This message got several candidates from find_security, and "
            + "the user has not chosen one. Do not place an order now: list the candidates and ask the user which one "
            + "they mean; the order can be placed after they answer.";

    static final String NO_ORDER_ON_A_SUGGESTION = "This message got only suggestions from find_security, not the "
            + "security the user named. Do not place an order now: ask the user whether they meant the suggested "
            + "security; the order can be placed after they answer.";

    private static final String PLACE_ORDER = "place_order";

    private final Function<ToolCall, ToolResult> delegate;
    /** Why other tools are refused, or null while they are allowed. */
    private String askFirst;
    /**
     * Why place_order is refused for the rest of this message, or null while it is allowed. Set when a search
     * returned CANDIDATES or suggestions; a later EXACT does not clear it, only the user's next message does.
     */
    private String orderRefusal;

    AmbiguityGuard(Function<ToolCall, ToolResult> delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolResult apply(ToolCall call) {
        if (askFirst != null && !ToolResults.FIND_SECURITY.equals(call.name())) {
            return ToolResult.error(call, askFirst);
        }
        if (orderRefusal != null && PLACE_ORDER.equals(call.name())) {
            return ToolResult.error(call, orderRefusal);
        }
        ToolResult result = delegate.apply(call);
        ToolResults.search(result).ifPresent(this::track);
        return result;
    }

    private void track(JsonNode search) {
        switch (ToolResults.matchOf(search)) {
            case CANDIDATES -> {
                askFirst = ASK_FIRST;
                orderRefusal = NO_ORDER_ON_A_CANDIDATE;
            }
            case EXACT -> askFirst = null;
            case NOT_FOUND -> {
                if (ToolResults.hasSuggestions(search)) {
                    askFirst = CONFIRM_SUGGESTION_FIRST;
                    orderRefusal = NO_ORDER_ON_A_SUGGESTION;
                }
            }
        }
    }
}
