package ai.lab.broker.domain;

import java.math.BigDecimal;

/** Security from the catalog; currentPrice is in USD. */
public record Security(String ticker, String isin, String name, BigDecimal currentPrice) {}
