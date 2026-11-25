package ai.lab.broker.domain;

import java.util.List;

/**
 * Result of a security search. EXACT — exactly one security (exact ticker/ISIN or the only name match);
 * CANDIDATES — several match and the user must choose; NOT_FOUND — nothing matches (the list is empty).
 * suggestions is only for NOT_FOUND: securities with a similar ticker or name ("did you mean"); they did not
 * match, so only the user can say whether one of them was meant.
 */
public record SearchResult(Match match, List<Security> securities, List<Security> suggestions) {

    public enum Match { EXACT, CANDIDATES, NOT_FOUND }

    public SearchResult {
        securities = List.copyOf(securities);
        suggestions = List.copyOf(suggestions);
    }

    public static SearchResult exact(Security security) {
        return new SearchResult(Match.EXACT, List.of(security), List.of());
    }

    public static SearchResult candidates(List<Security> securities) {
        return new SearchResult(Match.CANDIDATES, securities, List.of());
    }

    public static SearchResult notFound() {
        return notFound(List.of());
    }

    public static SearchResult notFound(List<Security> suggestions) {
        return new SearchResult(Match.NOT_FOUND, List.of(), suggestions);
    }
}
