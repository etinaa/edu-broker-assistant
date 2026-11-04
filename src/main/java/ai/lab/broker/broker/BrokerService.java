package ai.lab.broker.broker;

import ai.lab.broker.domain.Account;
import ai.lab.broker.domain.Order;
import ai.lab.broker.domain.OrderDecision;
import ai.lab.broker.domain.OrderSide;
import ai.lab.broker.domain.SearchResult;
import ai.lab.broker.domain.Security;

import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Все денежные правила — здесь, на BigDecimal, без LLM. Модель только понимает намерение и подбирает
 * аргументы; решает этот класс. Каждое правило должно быть покрыто юнит-тестом (задание 1).
 * <p>
 * Методы-заглушки бросают UnsupportedOperationException с номером задания — API отвечает на них 501.
 */
@Service
public class BrokerService {

    private final SecurityCatalog catalog;
    private final AccountRepository accounts;
    private final OrderBook orders;

    public BrokerService(SecurityCatalog catalog, AccountRepository accounts, OrderBook orders) {
        this.catalog = catalog;
        this.accounts = accounts;
        this.orders = orders;
    }

    /** Текущее состояние счёта. */
    public Account account() {
        return accounts.get();
    }

    /**
     * Задание 1. Поиск бумаги по тикеру, ISIN или названию:
     * <ol>
     *   <li>точный тикер или ISIN без учёта регистра → EXACT;</li>
     *   <li>иначе по названию (нормализованная подстрока: регистр, лишние пробелы, точки):
     *       одна бумага → EXACT, несколько → CANDIDATES;</li>
     *   <li>ничего → NOT_FOUND. Пустой или null-запрос — тоже NOT_FOUND, а не исключение.</li>
     * </ol>
     */
    public SearchResult findSecurity(String query) {
        throw new UnsupportedOperationException("Задание 1: BrokerService.findSecurity");
    }

    /** Текущая цена по точному тикеру. */
    public Optional<Security> quote(String ticker) {
        return catalog.byTicker(ticker);
    }

    /**
     * Задание 1. Создать заявку, ожидающую подтверждения. Деньги и бумаги НЕ двигаются.
     * <ul>
     *   <li>quantity ≤ 0 → Refused(INVALID_QUANTITY);</li>
     *   <li>неизвестный тикер → Refused(UNKNOWN_SECURITY);</li>
     *   <li>BUY: total = price × quantity; total &gt; cash → Refused(INSUFFICIENT_FUNDS) с суммой нехватки;</li>
     *   <li>SELL: quantity &gt; позиции → Refused(INSUFFICIENT_POSITION) с тем, сколько есть;</li>
     *   <li>иначе — Order(PENDING_CONFIRMATION) в {@link OrderBook} и Created с cashAfter.</li>
     * </ul>
     * Нецелое количество (2,5) отсекается раньше — при разборе аргументов инструмента (задание 5).
     * Подумай, учитывать ли в проверке баланса другие ожидающие заявки (задание 6а).
     */
    public OrderDecision placeOrder(OrderSide side, String ticker, long quantity) {
        throw new UnsupportedOperationException("Задание 1: BrokerService.placeOrder");
    }

    /**
     * Задание 1 (базовое исполнение) и задание 6 (повторные проверки). Только для PENDING_CONFIRMATION:
     * заново проверить деньги или позицию, при успехе изменить счёт и вернуть EXECUTED, при неуспехе — FAILED
     * с причиной. Повторный confirm не должен менять счёт второй раз.
     */
    public Order confirm(String orderId) {
        throw new UnsupportedOperationException("Задание 1: BrokerService.confirm");
    }

    /** Задание 1. Отказ от заявки: PENDING_CONFIRMATION → REJECTED_BY_USER, остальные статусы не меняются. */
    public Order reject(String orderId) {
        throw new UnsupportedOperationException("Задание 1: BrokerService.reject");
    }

    public Optional<Order> order(String orderId) {
        return orders.find(orderId);
    }
}
