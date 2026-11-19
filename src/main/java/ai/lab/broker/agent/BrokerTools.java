package ai.lab.broker.agent;

import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.domain.Account;
import ai.lab.broker.domain.Order;
import ai.lab.broker.domain.OrderDecision;
import ai.lab.broker.domain.OrderSide;
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
              "description": "Состояние счёта клиента: деньги в USD и все позиции (тикер, название бумаги, количество). Используй для вопросов «что у меня на счёте», «сколько у меня денег», «сколько у меня бумаг X».",
              "input_schema": {"type": "object", "properties": {}}
            }
            """;

    static final String FIND_SECURITY = """
            {
              "name": "find_security",
              "description": "Поиск бумаги по тикеру, ISIN или части названия. Возвращает match: EXACT — бумага определена однозначно; CANDIDATES — подходит несколько бумаг: покажи пользователю варианты (тикер и название) и попроси выбрать, сам не выбирай; NOT_FOUND — бумага не найдена, не придумывай тикер. Вызывай перед любым действием с бумагой, если пользователь не назвал точный тикер.",
              "input_schema": {
                "type": "object",
                "properties": {
                  "query": {"type": "string", "description": "Тикер (TRNC), ISIN (US0000TRNC07) или часть названия (Training) — как написал пользователь"}
                },
                "required": ["query"]
              }
            }
            """;

    static final String GET_QUOTE = """
            {
              "name": "get_quote",
              "description": "Текущая цена одной бумаги в USD по точному тикеру.",
              "input_schema": {
                "type": "object",
                "properties": {
                  "ticker": {"type": "string", "description": "Точный тикер из результата find_security, например TRNC"}
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
              "description": "Создать заявку на покупку (BUY) или продажу (SELL) бумаги. Заявка НЕ исполняется: она ждёт, пока пользователь подтвердит её в интерфейсе. Возвращает либо созданную заявку (orderId, цена, сумма, остаток денег после сделки), либо отказ с причиной и цифрами. Тикер — только из результата find_security или точно как назвал пользователь.",
              "input_schema": {
                "type": "object",
                "properties": {
                  "side":     {"type": "string", "enum": ["BUY", "SELL"], "description": "BUY — покупка, SELL — продажа"},
                  "ticker":   {"type": "string", "description": "Точный тикер, например DEMO"},
                  "quantity": {"type": "number", "description": "Количество бумаг в штуках, целое положительное"}
                },
                "required": ["side", "ticker", "quantity"]
              }
            }
            """;

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final BrokerService broker;
    private final ArrayNode definitions;

    public BrokerTools(BrokerService broker) {
        this.broker = broker;
        this.definitions = JSON.createArrayNode();
        for (String definition : List.of(GET_ACCOUNT_SUMMARY, FIND_SECURITY, GET_QUOTE, PLACE_ORDER)) {
            try {
                definitions.add(JSON.readTree(definition));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Битое определение инструмента: " + definition, e);
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
            default -> ToolResult.error(call, "Неизвестный инструмент " + call.name());
        };
    }

    /** Деньги и позиции счёта без номера счёта; название бумаги — из справочника. Нулевые позиции не показываем. */
    private ToolResult getAccountSummary(String accountId, ToolCall call) {
        Optional<Account> optAccount = broker.accountById(accountId);
        if (optAccount.isEmpty()) {
            return ToolResult.error(call, "Счёт не найден");
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

    /** Задание 3: broker.findSecurity(query) → JSON с match и списком бумаг. */
    private ToolResult findSecurity(ToolCall call) {
        throw new UnsupportedOperationException("Задание 3: find_security");
    }

    /** Задание 3: broker.quote(ticker) → цена или ошибка «бумага не найдена». */
    private ToolResult getQuote(ToolCall call) {
        throw new UnsupportedOperationException("Задание 3: get_quote");
    }

    /**
     * Разбирает аргументы (side из enum, ticker — непустая строка, quantity — целое положительное), вызывает
     * broker.placeOrder(accountId, …) и возвращает Created или Refused как обычный результат: отказ (в том числе
     * нецелое количество 2.5) — нормальный исход, который модель пересказывает. is_error — только для аргументов,
     * которые не соответствуют схеме (нет поля, неизвестный side): так модель понимает, что вызов надо исправить.
     */
    private ToolResult placeOrder(String accountId, ToolCall call) {
        JsonNode args = call.arguments() == null ? MissingNode.getInstance() : call.arguments();

        OrderSide side = parseSide(args.path("side"));
        if (side == null) {
            return ToolResult.error(call, "Аргумент side обязателен и должен быть BUY или SELL");
        }
        String ticker = args.path("ticker").asText("").strip();
        if (ticker.isEmpty()) {
            return ToolResult.error(call, "Аргумент ticker обязателен: непустая строка с точным тикером");
        }
        JsonNode quantityNode = args.path("quantity");
        if (quantityNode.isMissingNode() || quantityNode.isNull()) {
            return ToolResult.error(call, "Аргумент quantity обязателен: целое положительное число");
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
            default -> ToolResult.error(call, "Неожиданный результат создания заявки: " + decision);
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
