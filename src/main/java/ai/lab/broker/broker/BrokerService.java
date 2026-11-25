package ai.lab.broker.broker;

import ai.lab.broker.domain.*;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.Strings;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * All money rules live here, on BigDecimal, with no LLM involved. The model only understands the intent and
 * picks the arguments; this class makes the decisions. Every rule must be covered by a unit test (task 1).
 * <p>
 * Thread safety (task 6): methods that read and then change an order or an account are {@code synchronized}, so a
 * check ("status is PENDING", "enough cash") and the change that follows it cannot interleave with another request.
 * One lock for the whole service is enough for this in-memory demo; with a database it would be a lock per account.
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

    public Optional<Security> securityByTicker(String ticker) {
        return securities.byTicker(ticker);
    }

    public Optional<Account> accountById(String accountId) {
        return accounts.byId(accountId);
    }

    /**
     * A copy of the account taken under the service lock, for reading only: cash and positions are consistent with
     * each other and with the last completed operation, even while a confirmation is changing the account.
     */
    public synchronized Optional<Account> accountSnapshot(String accountId) {
        return accountById(accountId)
                .map(account -> new Account(account.getId(), account.getCash(), account.getPositions()));
    }

    public Optional<Order> orderById(String orderId) {
        return orders.byId(orderId);
    }

    /**
     * Task 1. Create an order that waits for confirmation. Money and securities are NOT moved.
     * <ul>
     *   <li>quantity ≤ 0 → Refused(INVALID_QUANTITY);</li>
     *   <li>unknown ticker → Refused(UNKNOWN_SECURITY);</li>
     *   <li>BUY: total = price × quantity; total &gt; cash → Refused(INSUFFICIENT_FUNDS) with the shortfall;</li>
     *   <li>SELL: quantity &gt; position → Refused(INSUFFICIENT_POSITION) with how many are available;</li>
     *   <li>otherwise — Order(PENDING_CONFIRMATION) in the {@link OrderBook} and Created with cashAfter.</li>
     * </ul>
     * A non-integer quantity (2.5) is rejected earlier, when the tool arguments are parsed (task 5).
     * Think about whether the balance check should account for other pending orders (task 6a).
     */
    public synchronized OrderDecision placeOrder(String accountId, OrderSide side, String ticker, long quantity) {
        Optional<Account> optAccount = accountById(accountId);
        if (optAccount.isEmpty()) {
            return OrderDecision.Refused.accountNotFound(accountId);
        }
        if (quantity <= 0) {
            return OrderDecision.Refused.invalidQuantity();
        }
        Optional<Security> optSecurity = securities.byTicker(ticker);
        if (optSecurity.isEmpty()) {
            return OrderDecision.Refused.unknownSecurity(ticker);
        }

        Account account = optAccount.get();
        Security security = optSecurity.get();
        BigDecimal total = BigDecimal.valueOf(quantity).multiply(security.currentPrice());
        if (side == OrderSide.BUY) {
            if (!account.enoughCash(total)) {
                return OrderDecision.Refused.insufficientFunds(total, account.getCash());
            }
        }
        if (side == OrderSide.SELL) {
            if (!account.enoughQuantity(security.ticker(), quantity)) {
                return OrderDecision.Refused.insufficientPosition(quantity,
                        account.quantityByTicker(security.ticker()));
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
     * Task 1 (basic execution) and task 6 (repeated checks). Only for PENDING_CONFIRMATION:
     * re-check the cash or the position at the current price; on success update the account, mark the order
     * EXECUTED and return Confirmed; on failure mark it FAILED and return Refused with the reason.
     * A repeated confirm is Refused and does not change the account a second time.
     */
    public synchronized OrderDecision confirmOrder(String accountId, String orderId) {
        Optional<Order> optOrder = orderById(orderId);
        if (optOrder.isEmpty()) {
            return OrderDecision.Refused.orderNotFound(orderId);
        }

        Order order = optOrder.get();
        Optional<Account> optAccount = accountById(accountId);
        if (optAccount.isEmpty()) {
            return OrderDecision.Refused.accountNotFound(accountId);
        }
        if (!Strings.CS.equals(order.getAccountId(), accountId)) {
            return OrderDecision.Refused.orderNotOwned(orderId, accountId);
        }
        Account account = optAccount.get();

        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            return OrderDecision.Refused.cannotConfirm(order.getStatus());
        }

        Optional<BigDecimal> optPrice = currentPriceByTicker(order.getTicker());
        if (optPrice.isEmpty()) {
            return failOrder(order, OrderDecision.Refused.unknownSecurity(order.getTicker()));
        }
        BigDecimal price = optPrice.get();
        long quantity = order.getQuantity();

        if (order.getSide() == OrderSide.BUY) {
            if (!account.add(order.getTicker(), quantity, price)) {
                BigDecimal total = BigDecimal.valueOf(quantity).multiply(price);
                return failOrder(order, OrderDecision.Refused.insufficientFunds(total, account.getCash()));
            }
        } else if (!account.subtract(order.getTicker(), quantity, price)) {
            return failOrder(order,
                    OrderDecision.Refused.insufficientPosition(quantity, account.quantityByTicker(order.getTicker())));
        }

        order.setStatus(OrderStatus.EXECUTED, "Order confirmed");
        return new OrderDecision.Confirmed(order);
    }

    /** The re-check at confirm time failed: the order becomes FAILED with the reason, the account stays untouched. */
    private static OrderDecision failOrder(Order order, OrderDecision.Refused refusal) {
        order.setStatus(OrderStatus.FAILED, refusal.message());
        return refusal;
    }

    /** Task 1. Reject an order: PENDING_CONFIRMATION → REJECTED_BY_USER, other statuses are left unchanged. */
    public synchronized OrderDecision rejectOrder(String accountId, String orderId) {
        Optional<Order> optOrder = orderById(orderId);
        if (optOrder.isEmpty()) {
            return OrderDecision.Refused.orderNotFound(orderId);
        }

        Order order = optOrder.get();
        Optional<Account> optAccount = accountById(accountId);
        if (optAccount.isEmpty()) {
            return OrderDecision.Refused.accountNotFound(accountId);
        }
        if (!Strings.CS.equals(order.getAccountId(), accountId)) {
            return OrderDecision.Refused.orderNotOwned(orderId, accountId);
        }

        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            return OrderDecision.Refused.cannotReject(order.getStatus());
        }

        Order canceledOrder = cancelOrder(order);
        return new OrderDecision.Rejected(canceledOrder);
    }

    private Order cancelOrder(Order order) {
        order.setStatus(OrderStatus.REJECTED_BY_USER, "Order cancelled by user");
        return order;
    }

    /**
     * Task 1. Find a security by ticker, ISIN or name:
     * <ol>
     *   <li>exact ticker or ISIN, case-insensitive → EXACT;</li>
     *   <li>otherwise by name (normalized substring: case, extra spaces, dots):
     *       one security → EXACT, several → CANDIDATES;</li>
     *   <li>nothing → NOT_FOUND. An empty or null query is also NOT_FOUND, not an exception.</li>
     * </ol>
     */
    public SearchResult findSecurity(String query) {
        if (query == null || query.isBlank()) {
            return SearchResult.notFound();
        }
        List<Security> all = securities.all();

        // 1. An exact ticker or ISIN identifies the security at once, even if other names would also match.
        String code = query.strip();
        Optional<Security> byCode = all.stream()
                .filter(security -> Strings.CI.equals(security.ticker(), code)
                        || Strings.CI.equals(security.isin(), code))
                .findFirst();
        if (byCode.isPresent()) {
            return SearchResult.exact(byCode.get());
        }

        // 2. Otherwise — a substring of the name after normalization.
        String normalizedQuery = normalizeName(query);
        if (normalizedQuery.isEmpty()) {
            return SearchResult.notFound();
        }
        List<Security> byName = all.stream()
                .filter(security -> normalizeName(security.name()).contains(normalizedQuery))
                .toList();

        // 3. Nothing → NOT_FOUND, one security → EXACT, several → CANDIDATES.
        if (byName.isEmpty()) {
            return SearchResult.notFound();
        } else if (byName.size() == 1) {
            return SearchResult.exact(byName.getFirst());
        } else {
            return SearchResult.candidates(byName);
        }
    }

    /** Case, dots and extra spaces do not affect the name search: "Sample  Logistics Ltd." ≡ "sample logistics ltd". */
    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT).replace('.', ' ').trim().replaceAll("\\s+", " ");
    }
}
