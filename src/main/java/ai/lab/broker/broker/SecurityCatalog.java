package ai.lab.broker.broker;

import ai.lab.broker.domain.Security;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Справочник бумаг в памяти. Только хранение; правила поиска (точное совпадение, кандидаты) —
 * в {@link BrokerService#findSecurity(String)}.
 */
@Component
public class SecurityCatalog {

    private final List<Security> securities = new CopyOnWriteArrayList<>();

    public SecurityCatalog() {
        reset();
    }

    public List<Security> all() {
        return List.copyOf(securities);
    }

    /** Точное совпадение тикера без учёта регистра. */
    public Optional<Security> byTicker(String ticker) {
        return securities.stream().filter(s -> s.ticker().equalsIgnoreCase(ticker)).findFirst();
    }

    /** Задание 6б: цена может измениться между созданием заявки и подтверждением. */
    public void updatePrice(String ticker, BigDecimal newPrice) {
        securities.replaceAll(s -> s.ticker().equalsIgnoreCase(ticker)
                ? new Security(s.ticker(), s.isin(), s.name(), newPrice)
                : s);
    }

    /** Задание 7: подменить бумагу, например название с инъекцией. */
    public void replace(Security security) {
        securities.replaceAll(s -> s.ticker().equalsIgnoreCase(security.ticker()) ? security : s);
    }

    public void reset() {
        securities.clear();
        securities.addAll(DemoData.SECURITIES);
    }
}
