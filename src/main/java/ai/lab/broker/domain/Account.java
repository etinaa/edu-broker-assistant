package ai.lab.broker.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Брокерский счёт: деньги в USD и позиции. Неизменяемый — любое изменение даёт новый экземпляр,
 * который сохраняется в {@link ai.lab.broker.broker.AccountRepository}.
 */
public record Account(String id, BigDecimal cash, List<Position> positions) {

    public Account {
        positions = List.copyOf(positions);
    }

    /** Количество бумаг на счёте; 0, если позиции нет. */
    public long quantityOf(String ticker) {
        return positions.stream()
                .filter(p -> p.ticker().equalsIgnoreCase(ticker))
                .mapToLong(Position::quantity)
                .sum();
    }
}
