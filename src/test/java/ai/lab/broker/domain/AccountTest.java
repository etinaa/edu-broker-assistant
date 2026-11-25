package ai.lab.broker.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {

    private Account account;

    @BeforeEach
    void setUp() {
        account = new Account("acc-1", new BigDecimal("1000"));
    }

    @Test
    void newAccountHasIdCashAndNoPositions() {
        assertEquals("acc-1", account.getId());
        assertEquals(new BigDecimal("1000"), account.getCash());
        assertTrue(account.getPositions().isEmpty());
    }

    @Test
    void constructorCopiesInitialPositions() {
        Map<String, Long> initial = Map.of("SBER", 5L);
        Account a = new Account("acc-2", BigDecimal.TEN, initial);

        assertEquals(5L, a.quantityByTicker("SBER"));
        assertNotSame(initial, a.getPositions());
    }

    @Test
    void positionsCannotBeChangedPastAddAndSubtract() {
        Account a = new Account("acc-3", BigDecimal.TEN, Map.of("SBER", 5L));

        assertThrows(UnsupportedOperationException.class, () -> a.getPositions().put("SBER", 1_000L));
        assertEquals(5L, a.quantityByTicker("SBER"));
    }

    @Test
    void quantityByTickerReturnsZeroForUnknownTicker() {
        assertEquals(0L, account.quantityByTicker("GAZP"));
    }

    @Test
    void buyAddsPositionAndPaysTheTotal() {
        assertTrue(account.buy("SBER", 3, new BigDecimal("300.30")));

        assertEquals(3L, account.quantityByTicker("SBER"));
        assertEquals(0, new BigDecimal("699.70").compareTo(account.getCash()));
    }

    @Test
    void buyAccumulatesExistingPosition() {
        account.buy("SBER", 2, new BigDecimal("200"));
        account.buy("SBER", 3, new BigDecimal("300"));

        assertEquals(5L, account.quantityByTicker("SBER"));
        assertEquals(0, new BigDecimal("500").compareTo(account.getCash()));
    }

    @Test
    void buyWithExactCashSucceeds() {
        assertTrue(account.buy("SBER", 10, new BigDecimal("1000")));
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getCash()));
    }

    @Test
    void buyFailsWhenNotEnoughCashAndLeavesStateUntouched() {
        assertFalse(account.buy("SBER", 10, new BigDecimal("1000.01")));

        assertEquals(0L, account.quantityByTicker("SBER"));
        assertEquals(new BigDecimal("1000"), account.getCash());
    }

    @Test
    void sellRemovesPositionAndReceivesTheTotal() {
        account.buy("SBER", 5, new BigDecimal("500"));

        assertTrue(account.sell("SBER", 2, new BigDecimal("239.76")));

        assertEquals(3L, account.quantityByTicker("SBER"));
        assertEquals(0, new BigDecimal("739.76").compareTo(account.getCash()));
    }

    @Test
    void sellAllSharesSucceeds() {
        account.buy("SBER", 5, new BigDecimal("500"));

        assertTrue(account.sell("SBER", 5, new BigDecimal("500")));
        assertEquals(0L, account.quantityByTicker("SBER"));
    }

    @Test
    void sellFailsWhenNotEnoughSharesAndLeavesStateUntouched() {
        account.buy("SBER", 2, new BigDecimal("200"));

        assertFalse(account.sell("SBER", 3, new BigDecimal("300")));

        assertEquals(2L, account.quantityByTicker("SBER"));
        assertEquals(0, new BigDecimal("800").compareTo(account.getCash()));
    }

    @Test
    void sellFailsForUnknownTicker() {
        assertFalse(account.sell("GAZP", 1, BigDecimal.TEN));
        assertEquals(new BigDecimal("1000"), account.getCash());
    }

    @Test
    void enoughCashReturnsTrueOnlyWhenCashCoversPrice() {
        assertFalse(account.enoughCash(new BigDecimal("1000.01")));
        assertTrue(account.enoughCash(new BigDecimal("1000")));
        assertTrue(account.enoughCash(new BigDecimal("1")));
    }

    @Test
    void enoughQuantityReturnsTrueOnlyWhenHeldCoversRequested() {
        account.buy("SBER", 5, new BigDecimal("5"));

        assertFalse(account.enoughQuantity("SBER", 6));
        assertTrue(account.enoughQuantity("SBER", 5));
        assertTrue(account.enoughQuantity("SBER", 1));
        assertFalse(account.enoughQuantity("GAZP", 1));
    }
}
