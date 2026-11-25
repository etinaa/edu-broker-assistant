package ai.lab.broker.agent;

import ai.lab.broker.llm.ToolConversation.Seen;
import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
 * requested ticker are not identifiers the model may use. Nor are the {@code suggestions} of a find_security that
 * found nothing (task 10) in the current message: "DEMA" → DEMO is a guess until the user answers. Suggestions from
 * earlier messages count, like earlier candidates: the user has seen them and may have answered "yes"
 * ({@link AmbiguityGuard} keeps place_order blocked for the rest of the message that got them).
 */
public final class GroundedExecutor implements Function<ToolCall, ToolResult> {

    /** Only identifiers are checked: the model must not derive them. */
    private static final List<String> IDENTIFIER_ARGUMENTS = List.of("ticker");

    /** Tokens are runs of letters, digits and hyphens: "TRNC," and "(trnc)" both give the token trnc. */
    private static final Pattern TOKEN_SEPARATORS = Pattern.compile("[^\\p{L}\\d-]+");

    private final Function<ToolCall, ToolResult> delegate;
    /** Everything seen, as lower-case tokens: each check is a set lookup instead of a regex over the history. */
    private final Set<String> seenTokens = new HashSet<>();

    public GroundedExecutor(Seen seenSoFar, Function<ToolCall, ToolResult> delegate) {
        this.delegate = delegate;
        seenSoFar.userMessages().forEach(this::addTokensOf);
        seenSoFar.toolResults().forEach(result -> addTickersOf(ToolResults.parse(result)));
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
            addMatchedTickersOf(result.content());
        }
        return result;
    }

    /** A result of this message: its tickers without the suggestions, which the user has not answered yet. */
    private void addMatchedTickersOf(String toolResult) {
        JsonNode root = ToolResults.parse(toolResult);
        if (root instanceof ObjectNode object) {
            object.remove(ToolResults.SUGGESTIONS);
        }
        addTickersOf(root);
    }

    /** Values of every ticker field, at any depth; a result that is not JSON parses as empty and adds nothing. */
    private void addTickersOf(JsonNode toolResult) {
        for (JsonNode ticker : toolResult.findValues("ticker")) {
            if (ticker.isTextual()) {
                addTokensOf(ticker.asText());
            }
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
