package ai.lab.broker.domain;

import java.util.List;

/**
 * Результат поиска бумаги. EXACT — ровно одна бумага (точный тикер/ISIN или единственное совпадение по названию),
 * CANDIDATES — несколько подходящих, нужно спросить пользователя, NOT_FOUND — ничего.
 */
public record SearchResult(Match match, List<Security> securities) {

    public enum Match { EXACT, CANDIDATES, NOT_FOUND }

    public SearchResult {
        securities = List.copyOf(securities);
    }

    public static SearchResult exact(Security security) {
        return new SearchResult(Match.EXACT, List.of(security));
    }

    public static SearchResult candidates(List<Security> securities) {
        return new SearchResult(Match.CANDIDATES, securities);
    }

    public static SearchResult notFound() {
        return new SearchResult(Match.NOT_FOUND, List.of());
    }
}
