package ai.lab.broker.broker;

import ai.lab.broker.domain.Security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * "Did you mean" for a search that found nothing (task 10): securities whose ticker or name is a few typos away
 * from the query, e.g. "Trainig" → the three Training securities, "DEMA" → DEMO.
 * <p>
 * The distance is Levenshtein's (insertions, deletions, substitutions), between the normalized query and the
 * lower-case ticker or any run of as many consecutive name words as the query has, so "Samle Logistics" is compared
 * with "sample logistics", not with the whole "sample logistics ltd". Only the closest securities are suggested.
 * The allowed distance grows with the query: short queries ("Tesla", "Apple") would otherwise match common words.
 * Queries shorter than 4 characters ("ink" → "Inc.") or longer than 64 are not corrected: the first are too
 * ambiguous, the second are no typo of a name and would only cost CPU on the public search endpoint.
 */
final class TypoSuggestions {

    private static final int MIN_QUERY_LENGTH = 4;
    private static final int MAX_QUERY_LENGTH = 64;

    private TypoSuggestions() {
    }

    /** normalizedQuery must be normalized like {@link BrokerService#normalizeName}: lower case, single spaces. */
    static List<Security> find(String normalizedQuery, List<Security> securities) {
        int length = normalizedQuery.length();
        if (length < MIN_QUERY_LENGTH || length > MAX_QUERY_LENGTH) {
            return List.of();
        }
        int allowed = allowedDistance(length);
        int queryWords = normalizedQuery.split(" ").length;
        int best = Integer.MAX_VALUE;
        List<Security> closest = new ArrayList<>();
        for (Security security : securities) {
            int distance = distance(normalizedQuery, queryWords, security, allowed);
            if (distance > allowed) {
                continue;
            }
            if (distance < best) {
                best = distance;
                closest.clear();
            }
            if (distance == best) {
                closest.add(security);
            }
        }
        return List.copyOf(closest);
    }

    private static int allowedDistance(int queryLength) {
        if (queryLength <= 5) {
            return 1;
        }
        return queryLength <= 10 ? 2 : 3;
    }

    /**
     * The smallest distance, up to limit + 1, from the query to the ticker or to a run of queryWords name words.
     */
    private static int distance(String query, int queryWords, Security security, int limit) {
        int best = levenshtein(query, security.ticker().toLowerCase(Locale.ROOT), limit);
        String[] words = BrokerService.normalizeName(security.name()).split(" ");
        int span = Math.min(queryWords, words.length);
        for (int start = 0; start + span <= words.length; start++) {
            String window = String.join(" ", Arrays.copyOfRange(words, start, start + span));
            best = Math.min(best, levenshtein(query, window, limit));
        }
        return best;
    }

    /**
     * Levenshtein distance by dynamic programming over two rows, cut off at limit: any distance above it is returned
     * as limit + 1. Strings whose lengths differ by more than limit are not compared at all, and a row whose every
     * cell is above limit ends the computation, so a long string costs little.
     */
    static int levenshtein(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) {
            return limit + 1;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
                rowMin = Math.min(rowMin, current[j]);
            }
            if (rowMin > limit) {
                return limit + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return Math.min(previous[b.length()], limit + 1);
    }
}
