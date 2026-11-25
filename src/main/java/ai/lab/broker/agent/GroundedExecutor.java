package ai.lab.broker.agent;

import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Checks arguments against what the model has actually seen (from lesson 1.4, task 5). A ticker in the arguments
 * must appear in the user's messages or in successful tool results, otherwise the model "corrected" or made it up.
 * <p>
 * Task 7: {@link AssistantService} creates one per message, seeded with {@code ToolConversation.groundingText()},
 * i.e. the whole session history. The instance then adds the results of its own calls as they come.
 */
public final class GroundedExecutor implements Function<ToolCall, ToolResult> {

    /** Only identifiers are checked: the model must not derive them. */
    private static final List<String> IDENTIFIER_ARGUMENTS = List.of("ticker");

    private final Function<ToolCall, ToolResult> delegate;
    private final StringBuilder seenText;

    public GroundedExecutor(String seenSoFar, Function<ToolCall, ToolResult> delegate) {
        this.delegate = delegate;
        this.seenText = new StringBuilder(seenSoFar);
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
            seenText.append('\n').append(result.content());
        }
        return result;
    }

    /** As a whole token, ignoring case: trnc → TRNC is an allowed normalization, TRN → TRNC is not. */
    private boolean wasSeen(String value) {
        Pattern token = Pattern.compile("(?<![\\p{L}\\d-])" + Pattern.quote(value) + "(?![\\p{L}\\d-])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return token.matcher(seenText).find();
    }
}
