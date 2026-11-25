package ai.lab.broker.broker;

import ai.lab.broker.domain.SearchResult;
import ai.lab.broker.domain.SearchResult.Match;
import ai.lab.broker.domain.Security;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** "Did you mean" through BrokerService.findSecurity on the demo catalog (task 10). */
class TypoSuggestionsTest {

    private final BrokerService broker = TestBroker.demo();

    private List<String> suggested(String query) {
        SearchResult result = broker.findSecurity(query);
        assertEquals(Match.NOT_FOUND, result.match(), query);
        assertTrue(result.securities().isEmpty(), query);
        return result.suggestions().stream().map(Security::ticker).toList();
    }

    @Test
    void aTypoInANameSuggestsEverySecurityWithThatName() {
        assertEquals(List.of("TRNC", "TRNP", "TREN"), suggested("Trainig"));
    }

    @Test
    void aTypoInATickerSuggestsThatTicker() {
        assertEquals(List.of("DEMO"), suggested("DEMA"));
    }

    @Test
    void aTypoInOneOfSeveralWordsIsComparedWithThatManyNameWords() {
        assertEquals(List.of("SMPL"), suggested("Samle Logistics"));
        assertEquals(List.of("TRNP"), suggested("Training Corp prefered"));
    }

    @Test
    void onlyTheClosestSecuritiesAreSuggested() {
        // "Sampl Bank" is 1 away from "sample bank", 2+ from "sample logistics".
        assertEquals(List.of("SMPB"), suggested("Sampl Bank"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tesla", "Apple", "Microsoft", "Amazon", "weather in Haifa", "zzz-nothing", "ab", "ink",
            "Demo Software Incorporated and a very long tail of words that no name in the catalog has"})
    void unrelatedOrTooShortQueriesSuggestNothing(String query) {
        assertEquals(List.of(), suggested(query));
    }

    @Test
    void foundSecuritiesHaveNoSuggestions() {
        assertTrue(broker.findSecurity("Training").suggestions().isEmpty());
        assertTrue(broker.findSecurity("DEMO").suggestions().isEmpty());
    }

    @Test
    void levenshteinCountsInsertionsDeletionsAndSubstitutions() {
        assertEquals(0, TypoSuggestions.levenshtein("demo", "demo", 5));
        assertEquals(1, TypoSuggestions.levenshtein("dema", "demo", 5));
        assertEquals(1, TypoSuggestions.levenshtein("trainig", "training", 5));
        assertEquals(1, TypoSuggestions.levenshtein("trainning", "training", 5));
        assertEquals(3, TypoSuggestions.levenshtein("kitten", "sitting", 5));
        assertEquals(4, TypoSuggestions.levenshtein("", "demo", 5));
    }

    @Test
    void levenshteinStopsAboveTheLimit() {
        assertEquals(3, TypoSuggestions.levenshtein("kitten", "sitting", 2));       // 3 > 2 → limit + 1
        assertEquals(2, TypoSuggestions.levenshtein("x".repeat(10_000), "demo", 1)); // lengths differ: not compared
        assertEquals(2, TypoSuggestions.levenshtein("abcd", "wxyz", 1));            // every row above the limit
        assertEquals(1, TypoSuggestions.levenshtein("dema", "demo", 1));
    }
}
