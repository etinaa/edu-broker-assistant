package ai.lab.broker.agent;

import ai.lab.broker.llm.ToolConversation.Seen;
import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Checks arguments against what the model has actually seen. A ticker in the arguments
 * must appear in the user's messages or in successful tool results, otherwise the model "corrected" or made it up.
 * <p>
 * Task 7: {@link AssistantService} creates one per message, seeded with {@code ToolConversation.seen()}, i.e. the
 * whole session history. The instance then adds the results of its own calls as they come.
 * <p>
 * The user's messages count as typed. From tool results only the values of {@code ticker} fields count: keys and
 * constants ({@code ticker}, {@code USD}, {@code EXACT}) and free text such as a refusal message that repeats the
 * requested ticker are not identifiers the model may use.
 */
public final class GroundedExecutor implements Function<ToolCall, ToolResult> {

    /** Only identifiers are checked: the model must not derive them. */
    private static final List<String> IDENTIFIER_ARGUMENTS = List.of("ticker");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Tokens are runs of letters, digits and hyphens: "TRNC," and "(trnc)" both give the token trnc. */
    private static final Pattern TOKEN_SEPARATORS = Pattern.compile("[^\\p{L}\\d-]+");

    private final Function<ToolCall, ToolResult> delegate;
    /** Everything seen, as lower-case tokens: each check is a set lookup instead of a regex over the history. */
    private final Set<String> seenTokens = new HashSet<>();

    public GroundedExecutor(Seen seenSoFar, Function<ToolCall, ToolResult> delegate) {
        this.delegate = delegate;
        seenSoFar.userMessages().forEach(this::addTokensOf);
        seenSoFar.toolResults().forEach(this::addTickersOf);
    }

    @Override
    public ToolResult apply(ToolCall call) {
        // Without arguments there is nothing to check: the tool itself reports the missing argument.
        JsonNode arguments = call.arguments() == null ? MissingNode.getInstance() : call.arguments();
        for (String argument : IDENTIFIER_ARGUMENTS) {
            // Stripped the same way BrokerTools strips it, so " TRNC" is checked as the TRNC the tool would use.
            String value = arguments.path(argument).asText("").strip();
            if (!value.isEmpty() && !wasSeen(value)) {
                return ToolResult.error(call, "Value " + argument + "='" + value + "' appears neither in the user's messages "
                        + "nor in tool results. Do not substitute or correct identifiers: "
                        + "find the security with find_security or ask the user.");
            }
        }
        ToolResult result = delegate.apply(call);
        if (!result.error()) {
            addTickersOf(result.content());
        }
        return result;
    }

    /** Values of every ticker field, at any depth; a result that is not JSON adds nothing. */
    private void addTickersOf(String toolResult) {
        try {
            for (JsonNode ticker : JSON.readTree(toolResult).findValues("ticker")) {
                if (ticker.isTextual()) {
                    addTokensOf(ticker.asText());
                }
            }
        } catch (JsonProcessingException e) {
            // Not JSON: free text is not a source of identifiers.
        }
    }

    private void addTokensOf(String text) {
        for (String token : TOKEN_SEPARATORS.split(text)) {
            if (!token.isEmpty()) {
                seenTokens.add(token.toLowerCase(Locale.ROOT));
            }
        }
    }

    /**
     * As a whole token, ignoring case: trnc → TRNC is an allowed normalization, TRN → TRNC is not. A value that is not
     * a single token (has spaces or dots) is never seen; the demo tickers are letters only.
     */
    private boolean wasSeen(String value) {
        return !TOKEN_SEPARATORS.matcher(value).find() && seenTokens.contains(value.toLowerCase(Locale.ROOT));
    }
}
