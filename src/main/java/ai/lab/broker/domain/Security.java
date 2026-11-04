package ai.lab.broker.domain;

import java.math.BigDecimal;

public record Security(String ticker, String isin, String name, BigDecimal price) {}
