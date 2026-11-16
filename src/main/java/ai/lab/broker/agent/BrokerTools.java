package ai.lab.broker.agent;

import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.llm.ToolConversation.ToolCall;
import ai.lab.broker.llm.ToolConversation.ToolResult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.util.List;

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

    /** Задание 3: broker.accountById(accountId) → JSON с деньгами и позициями (название бумаги — из справочника). */
    private ToolResult getAccountSummary(String accountId, ToolCall call) {
        throw new UnsupportedOperationException("Задание 3: get_account_summary");
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
     * Задание 5: разобрать и провалидировать аргументы (side из enum, quantity — целое положительное:
     * 2.5 → отказ), вызвать broker.placeOrder(accountId, …) и вернуть Created или Refused как обычный результат
     * (не is_error: отказ — нормальный исход, который модель пересказывает).
     */
    private ToolResult placeOrder(String accountId, ToolCall call) {
        throw new UnsupportedOperationException("Задание 5: place_order");
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
