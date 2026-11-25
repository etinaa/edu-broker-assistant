package ai.lab.broker.agent;

import ai.lab.broker.domain.SearchResult.Match;
import ai.lab.broker.llm.ToolConversation.ToolResult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;

import java.util.Optional;

/** Reading the JSON that {@link BrokerTools} returns: one place that knows what a result looks like. */
final class ToolResults {

    /** Exact BigDecimal for floats, so cashAfter keeps its scale (9629.50, not 9629.5). */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

    static final String FIND_SECURITY = "find_security";

    /** The "did you mean" field of a find_security result: similar securities, not ones that matched. */
    static final String SUGGESTIONS = "suggestions";

    private ToolResults() {
    }

    static boolean isSuccessOf(ToolResult result, String tool) {
        return !result.error() && tool.equals(result.call().name());
    }

    /** Unparsable content counts as an empty object. */
    static JsonNode parse(String content) {
        try {
            return JSON.readTree(content);
        } catch (JsonProcessingException e) {
            return JSON.createObjectNode();
        }
    }

    /** The body of a successful find_security result; empty for anything else. */
    static Optional<JsonNode> search(ToolResult result) {
        return isSuccessOf(result, FIND_SECURITY) ? Optional.of(parse(result.content())) : Optional.empty();
    }

    /** The match of a find_security body; a body without a known match (a missing node too) counts as NOT_FOUND. */
    static Match matchOf(JsonNode search) {
        return switch (search.path("match").asText()) {
            case "EXACT" -> Match.EXACT;
            case "CANDIDATES" -> Match.CANDIDATES;
            default -> Match.NOT_FOUND;
        };
    }

    /** Nothing matched, but there are securities that look like the query. */
    static boolean hasSuggestions(JsonNode search) {
        return matchOf(search) == Match.NOT_FOUND && !search.path(SUGGESTIONS).isEmpty();
    }

    /** The body of a place_order result that created an order (orderId, cashAfter, …); empty for anything else. */
    static Optional<JsonNode> createdOrder(ToolResult result) {
        if (!isSuccessOf(result, "place_order")) {
            return Optional.empty();
        }
        JsonNode body = parse(result.content());
        return "CREATED".equals(body.path("result").asText()) ? Optional.of(body) : Optional.empty();
    }
}
