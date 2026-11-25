package ai.lab.broker.broker;

import ai.lab.broker.config.BrokerProperties;
import ai.lab.broker.domain.*;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.Strings;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * All money rules live here, on BigDecimal, with no LLM involved. The model only understands the intent and
 * picks the arguments; this class makes the decisions. Every rule must be covered by a unit test (task 1).
 * <p>
 * Thread safety (task 6): methods that read and then change an order or an account are {@code synchronized}, so a
 * check ("status is PENDING", "enough cash") and the change that follows it cannot interleave with another request.
 * One lock for the whole service is enough for this in-memory demo; with a database it would be a lock per account.
 * <p>
 * Confirmation rules (task 6), set in {@link BrokerProperties}:
 * <ul>
 *   <li>an order lives {@code broker.order-ttl}; after that it is EXPIRED, checked when it is read or confirmed
 *       (no background job: nothing can happen to an order that nobody touches);</li>
 *   <li>the trade is executed at the current price, because that is the price the market gives now. If the price
 *       moved against the client (up for BUY, down for SELL) by more than {@code broker.max-price-move-percent},
 *       the order fails with PRICE_CHANGED and the client places a new one at the new price; a move in the
 *       client's favour is executed;</li>
 *   <li>cash and the position are re-checked at the current price, so two orders that each passed at placement
 *       cannot both spend the same money.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BrokerService {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final SecurityCatalog securities;
    private final AccountRepository accounts;
    private final OrderBook orders;
    private final BrokerProperties properties;
    private final Clock clock;

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

    /**
     * A snapshot of the order as of now, taken under the lock: a pending order past its lifetime is marked EXPIRED
     * first. Later changes do not show in the snapshot; read the order again to see them.
     */
    public synchronized Optional<Order> orderById(String orderId) {
        return liveOrder(orderId).map(Order::snapshot);
    }

    /** The stored order itself, for changes under the lock; never handed out. */
    private Optional<Order> liveOrder(String orderId) {
        Optional<Order> order = orders.byId(orderId);
        order.ifPresent(this::expireIfOverdue);
        return order;
    }

    private void expireIfOverdue(Order order) {
        Instant deadline = order.getCreatedAt().plus(properties.orderTtl());
        if (order.getStatus() == OrderStatus.PENDING_CONFIRMATION && Instant.now(clock).isAfter(deadline)) {
            order.setStatus(OrderStatus.EXPIRED, "Not confirmed within " + properties.orderTtl());
        }
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
        return new OrderDecision.Created(order.snapshot(), cashAfter);
    }

    private Order createOrder(String accountId, OrderSide side, String ticker, long quantity, BigDecimal price) {
        String orderId = orders.nextId();
        Order order = new Order(orderId, accountId, side, ticker, quantity, price, Instant.now(clock));
        orders.add(order);
        return order;
    }

    /**
     * Task 1 (basic execution) and task 6 (repeated checks). Only for PENDING_CONFIRMATION that has not expired:
     * check the price move, re-check the cash or the position at the current price; on success update the account,
     * mark the order EXECUTED and return Confirmed; on failure mark it FAILED and return Refused with the reason.
     * A repeated confirm is Refused and does not change the account a second time.
     */
    public synchronized OrderDecision confirmOrder(String accountId, String orderId) {
        Optional<Order> optOrder = liveOrder(orderId);
        if (optOrder.isEmpty()) {
            return OrderDecision.Refused.orderNotFound(orderId);
        }

        Order order = optOrder.get();
        if (!Strings.CS.equals(order.getAccountId(), accountId)) {
            return OrderDecision.Refused.orderNotFound(orderId);
        }
        Optional<Account> optAccount = accountById(accountId);
        if (optAccount.isEmpty()) {
            return OrderDecision.Refused.accountNotFound(accountId);
        }
        Account account = optAccount.get();

        if (order.getStatus() == OrderStatus.EXPIRED) {
            return OrderDecision.Refused.orderExpired(orderId);
        }
        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            return OrderDecision.Refused.cannotConfirm(order.getStatus());
        }

        Optional<BigDecimal> optPrice = currentPriceByTicker(order.getTicker());
        if (optPrice.isEmpty()) {
            return failOrder(order, OrderDecision.Refused.unknownSecurity(order.getTicker()));
        }
        BigDecimal price = optPrice.get();
        if (movedAgainstClient(order, price)) {
            return failOrder(order, OrderDecision.Refused.priceChanged(order.getTicker(), order.getPrice(), price,
                    properties.maxPriceMovePercent()));
        }
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

        order.setStatus(OrderStatus.EXECUTED, "Order confirmed at " + price.toPlainString() + " USD");
        return new OrderDecision.Confirmed(order.snapshot());
    }

    /** BUY: the price rose by more than allowed; SELL: it fell by more than allowed. Exactly at the limit is fine. */
    private boolean movedAgainstClient(Order order, BigDecimal currentPrice) {
        BigDecimal adverseMove = order.getSide() == OrderSide.BUY
                ? currentPrice.subtract(order.getPrice())
                : order.getPrice().subtract(currentPrice);
        BigDecimal allowed = order.getPrice().multiply(properties.maxPriceMovePercent())
                .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
        return adverseMove.compareTo(allowed) > 0;
    }

    /** The re-check at confirm time failed: the order becomes FAILED with the reason, the account stays untouched. */
    private static OrderDecision failOrder(Order order, OrderDecision.Refused refusal) {
        order.setStatus(OrderStatus.FAILED, refusal.message());
        return refusal;
    }

    /** Task 1. Reject an order: PENDING_CONFIRMATION → REJECTED_BY_USER, other statuses are left unchanged. */
    public synchronized OrderDecision rejectOrder(String accountId, String orderId) {
        Optional<Order> optOrder = liveOrder(orderId);
        if (optOrder.isEmpty()) {
            return OrderDecision.Refused.orderNotFound(orderId);
        }

        Order order = optOrder.get();
        if (!Strings.CS.equals(order.getAccountId(), accountId)) {
            return OrderDecision.Refused.orderNotFound(orderId);
        }
        Optional<Account> optAccount = accountById(accountId);
        if (optAccount.isEmpty()) {
            return OrderDecision.Refused.accountNotFound(accountId);
        }

        if (order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            return OrderDecision.Refused.cannotReject(order.getStatus());
        }

        order.setStatus(OrderStatus.REJECTED_BY_USER, "Order rejected by user");
        return new OrderDecision.Rejected(order.snapshot());
    }

    /**
     * Cancels an order the user never saw because the chat request that created it failed:
     * PENDING_CONFIRMATION → CANCELLED, so the audit trail does not claim a user decision. Returns false and changes
     * nothing for a missing or foreign order or one that is no longer pending.
     */
    public synchronized boolean cancelUnseenOrder(String accountId, String orderId, String reason) {
        Optional<Order> order = liveOrder(orderId)
                .filter(o -> Strings.CS.equals(o.getAccountId(), accountId))
                .filter(o -> o.getStatus() == OrderStatus.PENDING_CONFIRMATION);
        order.ifPresent(o -> o.setStatus(OrderStatus.CANCELLED, reason));
        return order.isPresent();
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
        return WHITESPACE.matcher(name.toLowerCase(Locale.ROOT).replace('.', ' ').trim()).replaceAll(" ");
    }
}
