package ai.lab.broker.domain;

import lombok.Getter;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class Account {

    private final String id;
    private final Map<String, Long> positions;
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

    public long quantityByTicker(String ticker) {
        return positions.getOrDefault(ticker, 0L);
    }

    public boolean add(Security security, long quantity) {
        return add(security.ticker(), quantity, security.currentPrice());
    }

    public boolean add(String ticker, long quantity, BigDecimal price) {
        BigDecimal totalPrice = price.multiply(BigDecimal.valueOf(quantity));
        if (!enoughCash(totalPrice)) {
            return false;
        }

        positions.put(ticker, positions.getOrDefault(ticker, 0L) + quantity);
        cash = cash.subtract(totalPrice);
        return true;
    }

    public boolean enoughCash(BigDecimal totalPrice) {
        return totalPrice.compareTo(cash) <= 0;
    }

    public boolean subtract(Security security, long quantity) {
        return subtract(security.ticker(), quantity, security.currentPrice());
    }

    public boolean subtract(String ticker, long quantity, BigDecimal price) {
        if (!enoughQuantity(ticker, quantity)) {
            return false;
        }
        positions.put(ticker, positions.getOrDefault(ticker, 0L) - quantity);
        BigDecimal fullPrice = price.multiply(BigDecimal.valueOf(quantity));
        cash = cash.add(fullPrice);
        return true;
    }

    public boolean enoughQuantity(String ticker, long quantity) {
        return positions.getOrDefault(ticker, 0L) >= quantity;
    }
}
