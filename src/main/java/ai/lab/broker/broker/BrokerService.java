package ai.lab.broker.broker;

import ai.lab.broker.domain.*;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.Strings;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Все денежные правила — здесь, на BigDecimal, без LLM. Модель только понимает намерение и подбирает
 * аргументы; решает этот класс. Каждое правило должно быть покрыто юнит-тестом (задание 1).
 * <p>
 * Методы-заглушки бросают UnsupportedOperationException с номером задания — API отвечает на них 501.
 */
@Service
@RequiredArgsConstructor
public class BrokerService {

    private final SecurityCatalog securities;
    private final AccountRepository accounts;
    private final OrderBook orders;

    public Optional<BigDecimal> currentPriceByTicker(String ticker) {
        return securities.byTicker(ticker).map(Security::currentPrice);
    }

    public Optional<Account> accountById(String accountId) {
        return accounts.byId(accountId);
    }

    public Optional<Order> orderById(String orderId) {
        return orders.byId(orderId);
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
    public OrderDecision placeOrder(String accountId, OrderSide side, String ticker, long quantity) {
        Optional<Account> optAccount = accountById(accountId);
        if (optAccount.isEmpty()) {
            return new OrderDecision.Refused(OrderDecision.Reason.ILLEGAL_ACCESS,
                    "Account with accountId = " + accountId + " not found");
        }
        if (quantity <= 0) {
            return new OrderDecision.Refused(OrderDecision.Reason.INVALID_QUANTITY,
                    "Quantity must be greater than 0");
        }
        Optional<Security> optSecurity = securities.byTicker(ticker);
        if (optSecurity.isEmpty()) {
            return new OrderDecision.Refused(OrderDecision.Reason.UNKNOWN_SECURITY,
                    "Security with ticker = " + ticker + " not found");
        }

        Account account = optAccount.get();
        Security security = optSecurity.get();
        BigDecimal total = BigDecimal.valueOf(quantity).multiply(security.currentPrice());
        if (side == OrderSide.BUY) {
            if (!account.enoughCash(total)) {
                return new OrderDecision.Refused(OrderDecision.Reason.INSUFFICIENT_FUNDS,
                        "Insufficient funds. Needed " + total + ". Available " + account.getCash());
            }
        }
        if (side == OrderSide.SELL) {
            long amount = account.quantityByTicker(security.ticker());
            if (!account.enoughQuantity(security.ticker(), quantity)) {
                return new OrderDecision.Refused(OrderDecision.Reason.INSUFFICIENT_POSITION,
                        "Insufficient securities. Needed " + quantity + ". Available " + amount);
            }
        }

        Order order = createOrder(accountId, side, security.ticker(), quantity, security.currentPrice());
        BigDecimal cashAfter = side == OrderSide.BUY
                ? account.getCash().subtract(total)
                : account.getCash().add(total);
        return new OrderDecision.Created(order, cashAfter);
    }

    private Order createOrder(String accountId, OrderSide side, String ticker, long quantity, BigDecimal price) {
        String orderId = orders.nextId();
        Order order = new Order(orderId, accountId, side, ticker, quantity, price);
        orders.add(order);
        return order;
    }

    /**
     * Задание 1 (базовое исполнение) и задание 6 (повторные проверки). Только для PENDING_CONFIRMATION:
     * заново проверить деньги или позицию, при успехе изменить счёт и вернуть EXECUTED, при неуспехе — FAILED
     * с причиной. Повторный confirm не должен менять счёт второй раз.
     */
    public OrderDecision confirmOrder(String accountId, String orderId) {
        Optional<Order> optOrder = orderById(orderId);
        if (optOrder.isEmpty()) {
            return new OrderDecision.Refused(OrderDecision.Reason.ILLEGAL_ACCESS,
                    "Order with orderId = " + orderId + " not found");
        }

        Order order = optOrder.get();
        Optional<Account> optAccount = accountById(accountId);
        if (optAccount.isEmpty()) {
            return new OrderDecision.Refused(OrderDecision.Reason.ILLEGAL_ACCESS,
                    "Account with accountId = " + accountId + " not found");
        }
        if (!Strings.CS.equals(order.getAccountId(), accountId)) {
            return new OrderDecision.Refused(OrderDecision.Reason.ILLEGAL_ACCESS,
                    "OrderId = " + orderId + " not related to accountId = " + accountId);
        }
        Account account = optAccount.get();

        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            return new OrderDecision.Refused(OrderDecision.Reason.ILLEGAL_ACCESS,
                    "Can't confirm order with status = " + order.getStatus());
        }

        BigDecimal price = currentPriceByTicker(order.getTicker()).orElse(BigDecimal.ZERO);
        BigDecimal total = BigDecimal.valueOf(order.getQuantity()).multiply(price);

        if (order.getSide() == OrderSide.BUY) {
            BigDecimal cash = account.getCash();
            if (total.compareTo(cash) > 0) {
                return new OrderDecision.Refused(OrderDecision.Reason.INSUFFICIENT_FUNDS,
                        "Insufficient funds. Needed " + total + ". Available " + cash);
            }
        }
        if (order.getSide() == OrderSide.SELL) {
            Long amount = account.getPositions().getOrDefault(order.getTicker(), 0L);
            if (order.getQuantity() > amount) {
                return new OrderDecision.Refused(OrderDecision.Reason.INSUFFICIENT_POSITION,
                        "Insufficient securities. Needed " + order.getQuantity() + ". Available " + amount);
            }
        }

        Order executedOrder = executeOrder(account, order);
        return new OrderDecision.Confirmed(executedOrder);
    }

    private Order executeOrder(Account account, Order order) {
        order.setStatus(OrderStatus.EXECUTED, "Order confirmed");
        String ticker = order.getTicker();
        BigDecimal price = currentPriceByTicker(ticker).orElse(BigDecimal.ZERO);
        if (order.getSide() == OrderSide.BUY) {
            account.add(ticker, order.getQuantity(), price);
        }
        if (order.getSide() == OrderSide.SELL) {
            account.subtract(ticker, order.getQuantity(), price);
        }
        return order;
    }

    /** Задание 1. Отказ от заявки: PENDING_CONFIRMATION → REJECTED_BY_USER, остальные статусы не меняются. */
    public OrderDecision rejectOrder(String accountId, String orderId) {
        Optional<Order> optOrder = orderById(orderId);
        if (optOrder.isEmpty()) {
            return new OrderDecision.Refused(OrderDecision.Reason.ILLEGAL_ACCESS,
                    "Order with orderId = " + orderId + " not found");
        }

        Order order = optOrder.get();
        Optional<Account> optAccount = accountById(accountId);
        if (optAccount.isEmpty()) {
            return new OrderDecision.Refused(OrderDecision.Reason.ILLEGAL_ACCESS,
                    "Account with accountId = " + accountId + " not found");
        }
        if (!Strings.CS.equals(order.getAccountId(), accountId)) {
            return new OrderDecision.Refused(OrderDecision.Reason.ILLEGAL_ACCESS,
                    "OrderId = " + orderId + " not related to accountId = " + accountId);
        }

        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            return new OrderDecision.Refused(OrderDecision.Reason.ILLEGAL_ACCESS,
                    "Can't reject order with status = " + order.getStatus());
        }

        Order canceledOrder = cancelOrder(order);
        return new OrderDecision.Rejected(canceledOrder);
    }

    private Order cancelOrder(Order order) {
        order.setStatus(OrderStatus.REJECTED_BY_USER, "Order cancelled by user");
        return order;
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
        List<Security> securityList = securities.all().stream()
                .filter(security -> Strings.CI.contains(security.isin(), query)
                        || Strings.CI.contains(security.ticker(), query)
                        || Strings.CI.contains(security.name(), query))
                .toList();
        if (securityList.isEmpty()) {
            return SearchResult.notFound();
        } else if (securityList.size() == 1) {
            return SearchResult.exact(securityList.getFirst());
        } else {
            return SearchResult.candidates(securityList);
        }
    }
}
