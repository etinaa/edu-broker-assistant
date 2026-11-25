package ai.lab.broker.broker;

import ai.lab.broker.domain.Account;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccountRepositoryTest {

    private AccountRepository repository;

    @BeforeEach
    void setUp() {
        repository = new AccountRepository();
    }

    @Test
    void allReturnsTheTwoDemoAccounts() {
        List<String> ids = repository.all().stream().map(Account::getId).toList();

        assertEquals(List.of("BRK-1", "BRK-2"), ids);
    }

    @Test
    void allReturnsUnmodifiableSnapshot() {
        List<Account> all = repository.all();

        assertThrows(UnsupportedOperationException.class,
                () -> all.add(new Account("X", BigDecimal.ZERO)));
        assertEquals(2, repository.all().size());
    }

    @Test
    void byIdReturnsFirstDemoAccountWithCashAndPositions() {
        Account account = repository.byId("BRK-1").orElseThrow();

        assertEquals(0, new BigDecimal("10000.00").compareTo(account.getCash()));
        assertEquals(20L, account.quantityByTicker("TRNC"));
        assertEquals(100L, account.quantityByTicker("SMPB"));
        assertEquals(5L, account.quantityByTicker("DEMO"));
        assertEquals(3, account.getPositions().size());
    }

    @Test
    void byIdReturnsSecondDemoAccountWithCashAndPositions() {
        Account account = repository.byId("BRK-2").orElseThrow();

        assertEquals(0, new BigDecimal("100.00").compareTo(account.getCash()));
        assertEquals(10L, account.quantityByTicker("SMPL"));
        assertEquals(1, account.getPositions().size());
    }

    @Test
    void byIdReturnsEmptyForUnknownId() {
        assertTrue(repository.byId("BRK-3").isEmpty());
        assertTrue(repository.byId("").isEmpty());
    }

    @Test
    void byIdReturnsEmptyForNull() {
        assertTrue(repository.byId(null).isEmpty());
    }

    @Test
    void byIdIsCaseSensitive() {
        assertTrue(repository.byId("brk-1").isEmpty());
    }

    @Test
    void byIdReturnsSameInstanceAsAll() {
        Account fromById = repository.byId("BRK-1").orElseThrow();

        assertTrue(repository.all().stream().anyMatch(a -> a == fromById));
        assertSame(fromById, repository.byId("BRK-1").orElseThrow());
    }

    @Test
    void accountChangesAreVisibleThroughRepository() {
        repository.byId("BRK-2").orElseThrow().buy("SMPL", 1, new BigDecimal("12.30"));

        assertEquals(11L, repository.byId("BRK-2").orElseThrow().quantityByTicker("SMPL"));
    }

    @Test
    void eachRepositoryInstanceHasIndependentState() {
        repository.byId("BRK-2").orElseThrow().buy("SMPL", 1, new BigDecimal("12.30"));

        assertEquals(10L, new AccountRepository().byId("BRK-2").orElseThrow().quantityByTicker("SMPL"));
    }
}
