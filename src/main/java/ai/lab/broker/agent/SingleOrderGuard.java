package ai.lab.broker.agent;

import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.function.Function;

/**
 * One order per message. The response carries a single pendingOrder, so a second order created in the same message
 * (a retry, or parallel place_order calls) would stay confirmable through /orders/{id}/confirm while the user never
 * saw it. After a place_order that created an order, every further place_order of the message is refused without
 * running, and the model tells the user to deal with the first order before the next one.
 * <p>
 * A refused place_order does not count: the model may fix the arguments and try again.
 * One instance per message, like {@link AmbiguityGuard}.
 */
final class SingleOrderGuard implements Function<ToolCall, ToolResult> {

    static final String ONE_ORDER = "Order %s was already created in this message and waits for the user's "
            + "confirmation. Only one order per message: do not create another one. Tell the user to confirm or reject "
            + "%s first, then ask for the next order.";

    private static final String PLACE_ORDER = "place_order";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Function<ToolCall, ToolResult> delegate;
    private String createdOrderId;

    SingleOrderGuard(Function<ToolCall, ToolResult> delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolResult apply(ToolCall call) {
        boolean placeOrder = PLACE_ORDER.equals(call.name());
        if (placeOrder && createdOrderId != null) {
            return ToolResult.error(call, ONE_ORDER.formatted(createdOrderId, createdOrderId));
        }
        ToolResult result = delegate.apply(call);
        if (placeOrder && !result.error()) {
            JsonNode body = parse(result.content());
            if ("CREATED".equals(body.path("result").asText())) {
                createdOrderId = body.path("orderId").asText();
            }
        }
        return result;
    }

    private static JsonNode parse(String content) {
        try {
            return JSON.readTree(content);
        } catch (JsonProcessingException e) {
            return JSON.createObjectNode();
        }
    }
}
