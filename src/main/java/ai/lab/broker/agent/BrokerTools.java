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
 * Инструменты ассистента: определения (в формате Claude: name, description, input_schema) и их выполнение
 * через {@link BrokerService}. Описание инструмента — это промпт: модель выбирает инструмент и заполняет
 * аргументы только по нему. Подтверждения сделки среди инструментов нет и не будет.
 * <p>
 * Счёт модель не знает и не выбирает: {@code accountId} приходит из запроса (заголовок X-Account-Id) и передаётся
 * в {@link #execute} кодом. В схемах инструментов параметра счёта нет, а результаты инструментов не должны его
 * содержать: сериализуй не {@code Account}/{@code Order} целиком (там есть id и accountId), а представления без номера счёта.
 */
@Component
public class BrokerTools {

    static final String GET_ACCOUNT_SUMMARY = """
            {
              "name": "get_account_summary",
              "description": "The client's account state: cash in USD and all positions (ticker, security name, quantity). Use it for questions like 'what is in my account', 'how much cash do I have', 'how many X do I have'.",
              "input_schema": {"type": "object", "properties": {}}
            }
            """;

    static final String FIND_SECURITY = """
            {
              "name": "find_security",
              "description": "Find a security by ticker, ISIN or part of its name. Returns match: EXACT — the security is identified unambiguously; CANDIDATES — several securities match: show the user the options (ticker and name) and ask them to choose, do not choose yourself; NOT_FOUND — no security found, do not make up a ticker. Call it before any action with a security if the user did not give an exact ticker.",
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
     * quantity в схеме — number, а не integer: пусть нецелое количество доходит до кода и отклоняется там
     * (вопрос 10). Сравни, что меняется, если поставить integer.
     */
    static final String PLACE_ORDER = """
            {
              "name": "place_order",
              "description": "Create an order to buy (BUY) or sell (SELL) a security. The order is NOT executed: it waits until the user confirms it in the UI. Returns either the created order (orderId, price, total, cash left after the trade) or a refusal with the reason and numbers. The ticker must come from a find_security result or be exactly as the user named it.",
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

    /** Определения для запроса к модели. Копия — чтобы диалог не мог случайно изменить общий экземпляр. */
    public ArrayNode definitions() {
        return definitions.deepCopy();
    }

    /** Выполнение вызова от имени счёта accountId. Неизвестный инструмент — ошибка для модели, а не исключение. */
    public ToolResult execute(String accountId, ToolCall call) {
        return switch (call.name()) {
            case "get_account_summary" -> getAccountSummary(accountId, call);
            case "find_security" -> findSecurity(call);
            case "get_quote" -> getQuote(call);
            case "place_order" -> placeOrder(accountId, call);
            default -> ToolResult.error(call, "Unknown tool " + call.name());
        };
    }

    /** Деньги и позиции счёта без номера счёта; название бумаги — из справочника. Нулевые позиции не показываем. */
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
     * broker.findSecurity(query) → JSON с match и списком бумаг (тикер, ISIN, название). Пустой запрос — это
     * NOT_FOUND от сервиса, а не is_error; is_error только если поля query нет или оно не строка.
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
        return ToolResult.ok(call, toJson(view));
    }

    /** Аргументы вызова; если модель их не прислала — «пустой» узел, чтобы path() не падал на null. */
    private static JsonNode argumentsOf(ToolCall call) {
        return call.arguments() == null ? MissingNode.getInstance() : call.arguments();
    }

    /** Тикер из аргументов без пробелов по краям; пустая строка, если его нет. */
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

    /** Цена бумаги по точному тикеру (в ответе канонический тикер из справочника); неизвестный тикер — is_error. */
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
     * Разбирает аргументы (side из enum, ticker — непустая строка, quantity — целое положительное), вызывает
     * broker.placeOrder(accountId, …) и возвращает Created или Refused как обычный результат: отказ (в том числе
     * нецелое количество 2.5) — нормальный исход, который модель пересказывает. is_error — только для аргументов,
     * которые не соответствуют схеме (нет поля, неизвестный side): так модель понимает, что вызов надо исправить.
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

    /** Нецелое количество — отказ того же вида, что и остальные, чтобы модель пересказала его как обычный исход. */
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

    /** Целое количество из числа или строки ("5", 5, 5.0); 2.5, "abc", слишком большое число — null. */
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

    /** Представление созданной заявки для модели: id заявки есть, номера счёта нет. */
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
        view.put("total", order.total());
        view.put("cashAfter", created.cashAfter());
        return view;
    }

    /** Отказ с числами из кода (needed/available/shortfall — null для отказов без цифр). */
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

    /** Сериализация результатов инструментов: BigDecimal как числа, даты — ISO-строкой. */
    static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
