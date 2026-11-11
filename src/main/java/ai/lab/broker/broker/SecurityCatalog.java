package ai.lab.broker.broker;

import ai.lab.broker.domain.Security;

import org.apache.commons.lang3.Strings;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class SecurityCatalog {

    public static final List<Security> DEMO_SECURITIES = List.of(
            new Security("TRNC", "US0000TRNC07", "Training Corp. common stock", new BigDecimal("185.25")),
            new Security("TRNP", "US0000TRNP02", "Training Corp. preferred stock", new BigDecimal("24.10")),
            new Security("TREN", "US0000TREN05", "Training Energy Inc.", new BigDecimal("62.40")),
            new Security("SMPB", "US0000SMPB02", "Sample Bank Holdings", new BigDecimal("41.75")),
            new Security("SMPL", "US0000SMPL00", "Sample Logistics Ltd.", new BigDecimal("12.30")),
            new Security("DEMO", "US0000DEMO07", "Demo Software Inc.", new BigDecimal("410.00")));

    private final List<Security> securities;

    public SecurityCatalog() {
        securities = new CopyOnWriteArrayList<>();
        securities.addAll(DEMO_SECURITIES);
    }

    public List<Security> all() {
        return List.copyOf(securities);
    }

    public Optional<Security> byTicker(String ticker) {
        return securities.stream()
                .filter(security -> Strings.CI.equals(security.ticker(), ticker))
                .findFirst();
    }

    public Optional<Security> byIsin(String isin) {
        return securities.stream()
                .filter(security -> Strings.CI.equals(security.isin(), isin))
                .findFirst();
    }
}
