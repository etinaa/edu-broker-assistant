package ai.lab.broker.domain;

import java.util.List;

/**
 * Result of a security search. EXACT — exactly one security (exact ticker/ISIN or the only name match);
 * CANDIDATES — several match and the user must choose; NOT_FOUND — nothing matches (the list is empty).
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
