package ai.lab.broker.domain;

/** Сколько бумаг с тикером лежит на счёте. */
public record Position(String ticker, long quantity) {}
