package ai.lab.broker.agent;

import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.domain.Account;
import ai.lab.broker.domain.Order;
import ai.lab.broker.domain.OrderDecision;
import ai.lab.broker.domain.OrderSide;
import ai.lab.broker.domain.SearchResult;
import ai.lab.broker.domain.Security;
import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The assistant's tools: definitions (in Claude's format: name, description, input_schema) and their execution
 * through {@link BrokerService}. A tool description is a prompt: the model picks the tool and fills in the
 * arguments from it alone. Confirming a trade is not a tool and never will be.
 * <p>
 * The model neither knows nor chooses the account: {@code accountId} comes from the request (the X-Account-Id
 * header) and code passes it to {@link #execute}. The tool schemas have no account parameter, and tool results must
 * not contain it either: serialize views without the account number, not whole {@code Account}/{@code Order}
 * objects (they hold id and accountId).
 */
@Component
public class BrokerTools {

    static final String GET_ACCOUNT_SUMMARY = """
            {
              "name": "get_account_summary",
              "description": "The client's account state: cash in USD and all positions (ticker, security name, quantity). Use it for questions like 'what is in my account', 'how much cash do I have', 'how many TRNC do I have'. If the user names a security by name rather than an exact ticker or ISIN, call find_security first: a name may match several securities, and the account holds only some of them.",
              "input_schema": {"type": "object", "properties": {}}
            }
            """;

    static final String FIND_SECURITY = """
            {
              "name": "find_security",
              "description": "Find a security by ticker, ISIN or part of its name. Returns match: EXACT — the security is identified unambiguously; CANDIDATES — several securities match: show the user the options (ticker and name) and ask them to choose, do not choose yourself; NOT_FOUND — no security found, do not make up a ticker. Call it before any action with a security, and before answering any question about one (price, how many the user holds), if the user did not give an exact ticker or ISIN.",
              "input_schema": {
                "type": "object",
                "properties": {
                  "query": {"type": "string", "description": "Ticker (TRNC), ISIN (US0000TRNC07) or part of the name (Training), as the user wrote it"}
                },
                "required": ["query"]
              }
            }
            """;

    static final String GET_QUOTE = """
            {
              "name": "get_quote",
              "description": "Current price of one security in USD by exact ticker.",
              "input_schema": {
                "type": "object",
                "properties": {
                  "ticker": {"type": "string", "description": "Exact ticker from a find_security result, e.g. TRNC"}
                },
                "required": ["ticker"]
              }
            }
            """;

    /**
     * quantity is number in the schema, not integer: a non-integer quantity should reach the code and be refused
     * there (question 10). Compare what changes with integer.
     */
    static final String PLACE_ORDER = """
            {
              "name": "place_order",
              "description": "Create an order to buy (BUY) or sell (SELL) a security. The order is NOT executed: it waits until the user confirms it in the UI. Returns either the created order (orderId, price, the broker fee, total including the fee, cash left after the trade) or a refusal with the reason and numbers. The ticker must come from a find_security result or be exactly as the user named it.",
              "input_schema": {
                "type": "object",
                "properties": {
                  "side":     {"type": "string", "enum": ["BUY", "SELL"], "description": "BUY to buy, SELL to sell"},
                  "ticker":   {"type": "string", "description": "Exact ticker, e.g. DEMO"},
                  "quantity": {"type": "number", "description": "Number of shares, a whole positive number"}
                },
                "required": ["side", "ticker", "quantity"]
              }
            }
            """;

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String TICKER_REQUIRED = "Argument ticker is required: a non-empty string with the exact ticker";

    private final BrokerService broker;
    private final ArrayNode definitions;

    public BrokerTools(BrokerService broker) {
        this.broker = broker;
        this.definitions = JSON.createArrayNode();
        for (String definition : List.of(GET_ACCOUNT_SUMMARY, FIND_SECURITY, GET_QUOTE, PLACE_ORDER)) {
            try {
                definitions.add(JSON.readTree(definition));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Malformed tool definition: " + definition, e);
            }
        }
    }

    /** Definitions for the model request. A copy, so a conversation cannot change the shared instance by accident. */
    public ArrayNode definitions() {
        return definitions.deepCopy();
    }

    /** Runs a call on behalf of account accountId. An unknown tool is an error for the model, not an exception. */
    public ToolResult execute(String accountId, ToolCall call) {
        return switch (call.name()) {
            case "get_account_summary" -> getAccountSummary(accountId, call);
            case "find_security" -> findSecurity(call);
            case "get_quote" -> getQuote(call);
            case "place_order" -> placeOrder(accountId, call);
            default -> ToolResult.error(call, "Unknown tool " + call.name());
        };
    }

    /** Cash and positions without the account number; security names come from the catalog. Zero positions are left out. */
    private ToolResult getAccountSummary(String accountId, ToolCall call) {
        Optional<Account> optAccount = broker.accountSnapshot(accountId);
        if (optAccount.isEmpty()) {
            return ToolResult.error(call, "Account not found");
        }
        Account account = optAccount.get();

        List<Map<String, Object>> positions = account.getPositions().entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> positionView(entry.getKey(), entry.getValue()))
                .toList();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("cash", account.getCash());
        view.put("currency", "USD");
        view.put("positions", positions);
        return ToolResult.ok(call, toJson(view));
    }

    private Map<String, Object> positionView(String ticker, long quantity) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ticker", ticker);
        view.put("name", broker.securityByTicker(ticker).map(Security::name).orElse(null));
        view.put("quantity", quantity);
        return view;
    }

    /**
     * broker.findSecurity(query) → JSON with match and the list of securities (ticker, ISIN, name). An empty query
     * is NOT_FOUND from the service, not is_error; is_error only if query is missing or not a string.
     */
    private ToolResult findSecurity(ToolCall call) {
        JsonNode args = argumentsOf(call);
        JsonNode queryNode = args.path("query");
        if (!queryNode.isTextual()) {
            return ToolResult.error(call, "Argument query is required: a string with a ticker, ISIN or part of a name");
        }

        SearchResult result = broker.findSecurity(queryNode.asText());
        List<Map<String, Object>> securities = result.securities().stream()
                .map(BrokerTools::securityView)
                .toList();

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("match", result.match());
        view.put("securities", securities);
        if (result.match() == SearchResult.Match.CANDIDATES) {
            // Right where the model decides what to do next: a local model skipped this rule in the description.
            view.put("nextStep", AmbiguityGuard.ASK_FIRST);
        }
        return ToolResult.ok(call, toJson(view));
    }

    /** The call arguments; a missing node if the model sent none, so path() does not fail on null. */
    private static JsonNode argumentsOf(ToolCall call) {
        return call.arguments() == null ? MissingNode.getInstance() : call.arguments();
    }

    /** The ticker from the arguments without surrounding spaces; an empty string if there is none. */
    private static String tickerOf(JsonNode args) {
        return args.path("ticker").asText("").strip();
    }

    private static Map<String, Object> securityView(Security security) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ticker", security.ticker());
        view.put("isin", security.isin());
        view.put("name", security.name());
        return view;
    }

    /** The price by exact ticker (the result has the canonical ticker from the catalog); an unknown ticker is is_error. */
    private ToolResult getQuote(ToolCall call) {
        String ticker = tickerOf(argumentsOf(call));
        if (ticker.isEmpty()) {
            return ToolResult.error(call, TICKER_REQUIRED);
        }

        Optional<Security> security = broker.securityByTicker(ticker);
        if (security.isEmpty()) {
            return ToolResult.error(call, "Security with ticker " + ticker + " not found. Find it with find_security");
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ticker", security.get().ticker());
        view.put("price", security.get().currentPrice());
        view.put("currency", "USD");
        return ToolResult.ok(call, toJson(view));
    }

    /**
     * Parses the arguments (side from the enum, ticker a non-empty string, quantity a positive integer), calls
     * broker.placeOrder(accountId, ...) and returns Created or Refused as an ordinary result: a refusal (including a
     * non-integer quantity of 2.5) is a normal outcome the model relays. is_error is only for arguments that break
     * the schema (a missing field, an unknown side): that tells the model the call itself must be fixed.
     */
    private ToolResult placeOrder(String accountId, ToolCall call) {
        JsonNode args = argumentsOf(call);

        OrderSide side = parseSide(args.path("side"));
        if (side == null) {
            return ToolResult.error(call, "Argument side is required and must be BUY or SELL");
        }
        String ticker = tickerOf(args);
        if (ticker.isEmpty()) {
            return ToolResult.error(call, TICKER_REQUIRED);
        }
        JsonNode quantityNode = args.path("quantity");
        if (quantityNode.isMissingNode() || quantityNode.isNull()) {
            return ToolResult.error(call, "Argument quantity is required: a whole positive number");
        }

        return toToolResult(call, decide(accountId, side, ticker, quantityNode));
    }

    /** A non-integer quantity is a refusal like the others, so the model relays it as an ordinary outcome. */
    private OrderDecision decide(String accountId, OrderSide side, String ticker, JsonNode quantityNode) {
        Long quantity = parseWholeQuantity(quantityNode);
        if (quantity == null) {
            return OrderDecision.Refused.nonIntegerQuantity(quantityNode.asText());
        }
        return broker.placeOrder(accountId, side, ticker, quantity);
    }

    private static ToolResult toToolResult(ToolCall call, OrderDecision decision) {
        return switch (decision) {
            case OrderDecision.Created created -> ToolResult.ok(call, toJson(createdView(created)));
            case OrderDecision.Refused refused -> ToolResult.ok(call, toJson(refusedView(refused)));
            default -> ToolResult.error(call, "Unexpected order creation result: " + decision);
        };
    }

    private static OrderSide parseSide(JsonNode node) {
        if (!node.isTextual()) {
            return null;
        }
        try {
            return OrderSide.valueOf(node.asText().strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** An integer quantity from a number or a string ("5", 5, 5.0); 2.5, "abc" or a number too large give null. */
    private static Long parseWholeQuantity(JsonNode node) {
        BigDecimal value;
        if (node.isNumber()) {
            value = node.decimalValue();
        } else if (node.isTextual()) {
            try {
                value = new BigDecimal(node.asText().strip());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            return null;
        }
    }

    /** The created order as the model sees it: the order id is there, the account number is not. */
    private static Map<String, Object> createdView(OrderDecision.Created created) {
        Order order = created.order();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("result", "CREATED");
        view.put("orderId", order.getId());
        view.put("status", order.getStatus());
        view.put("side", order.getSide());
        view.put("ticker", order.getTicker());
        view.put("quantity", order.getQuantity());
        view.put("price", order.getPrice());
        view.put("fee", order.getFee());
        view.put("total", order.total());
        view.put("cashAfter", created.cashAfter());
        return view;
    }

    /** A refusal with numbers from code (needed/available/shortfall are null for refusals without numbers). */
    private static Map<String, Object> refusedView(OrderDecision.Refused refused) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("result", "REFUSED");
        view.put("reason", refused.reason());
        view.put("message", refused.message());
        view.put("needed", refused.needed());
        view.put("available", refused.available());
        view.put("shortfall", refused.shortfall());
        return view;
    }

    /** Serializes tool results: BigDecimal as numbers, dates as ISO strings. */
    static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
