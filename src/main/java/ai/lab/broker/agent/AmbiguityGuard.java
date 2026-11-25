package ai.lab.broker.agent;

import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.function.Function;

/**
 * Question 4: after find_security returned CANDIDATES, the model must ask the user, not pick a security itself.
 * The prompt and the tool description say so, but a local model still answered about one of the candidates. So
 * code enforces it: until a later find_security in the same message narrows the search down to EXACT, every other
 * tool call is refused, and the model has nothing to answer with but the question to the user.
 * <p>
 * One instance per message: the user's next message ("Logistics") starts with a clean state.
 */
final class AmbiguityGuard implements Function<ToolCall, ToolResult> {

    static final String ASK_FIRST = "Several securities matched the last find_security. Do not choose one and do "
            + "not answer about any of them: list their tickers and names and ask the user which one they mean.";

    private static final String FIND_SECURITY = "find_security";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Function<ToolCall, ToolResult> delegate;
    private boolean ambiguous;

    AmbiguityGuard(Function<ToolCall, ToolResult> delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolResult apply(ToolCall call) {
        if (ambiguous && !FIND_SECURITY.equals(call.name())) {
            return ToolResult.error(call, ASK_FIRST);
        }
        ToolResult result = delegate.apply(call);
        if (FIND_SECURITY.equals(call.name()) && !result.error()) {
            String match = matchOf(result.content());
            if ("CANDIDATES".equals(match)) {
                ambiguous = true;
            } else if ("EXACT".equals(match)) {
                ambiguous = false;
            }
        }
        return result;
    }

    private static String matchOf(String content) {
        try {
            return JSON.readTree(content).path("match").asText();
        } catch (JsonProcessingException e) {
            return "";
        }
    }
}
