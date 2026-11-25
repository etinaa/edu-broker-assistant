package ai.lab.broker.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brokerage account: cash in USD and positions (ticker → quantity). Not synchronized by itself;
 * {@code BrokerService} serializes the operations that change it.
 */
public class Account {

    @Getter
    private final String id;
    private final Map<String, Long> positions;
    @Getter
    private BigDecimal cash;

    public Account(String id, BigDecimal cash) {
        this.id = id;
        this.cash = cash;
        this.positions = new ConcurrentHashMap<>();
    }

    public Account(String id, BigDecimal cash, Map<String, Long> positions) {
        this(id, cash);
        this.positions.putAll(positions);
    }

    /** A read-only view: positions change only through {@link #buy} and {@link #sell}, with the cash. */
    public Map<String, Long> getPositions() {
        return Collections.unmodifiableMap(positions);
    }

    public long quantityByTicker(String ticker) {
        return positions.getOrDefault(ticker, 0L);
    }

    /**
     * Buys: adds the position and pays total (amount + fee, from {@link TradeCost}).
     * Returns false and changes nothing if cash is short.
     */
    public boolean buy(String ticker, long quantity, BigDecimal total) {
        if (!enoughCash(total)) {
            return false;
        }
        positions.put(ticker, positions.getOrDefault(ticker, 0L) + quantity);
        cash = cash.subtract(total);
        return true;
    }

    public boolean enoughCash(BigDecimal total) {
        return total.compareTo(cash) <= 0;
    }

    /**
     * Sells: removes the position and receives total (amount − fee, from {@link TradeCost}).
     * Returns false and changes nothing if the position is short.
     */
    public boolean sell(String ticker, long quantity, BigDecimal total) {
        if (!enoughQuantity(ticker, quantity)) {
            return false;
        }
        positions.put(ticker, positions.getOrDefault(ticker, 0L) - quantity);
        cash = cash.add(total);
        return true;
    }

    public boolean enoughQuantity(String ticker, long quantity) {
        return positions.getOrDefault(ticker, 0L) >= quantity;
    }
}
