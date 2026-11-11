package ai.lab.broker.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AccountTest {

    private static final Security SBER =
            new Security("SBER", "RU0009029540", "Sberbank", new BigDecimal("100"));

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
    void quantityByTickerReturnsZeroForUnknownTicker() {
        assertEquals(0L, account.quantityByTicker("GAZP"));
    }

    @Test
    void addBuysPositionAndReducesCash() {
        assertTrue(account.add("SBER", 3, new BigDecimal("100")));

        assertEquals(3L, account.quantityByTicker("SBER"));
        assertEquals(0, new BigDecimal("700").compareTo(account.getCash()));
    }

    @Test
    void addAccumulatesExistingPosition() {
        account.add("SBER", 2, new BigDecimal("100"));
        account.add("SBER", 3, new BigDecimal("100"));

        assertEquals(5L, account.quantityByTicker("SBER"));
        assertEquals(0, new BigDecimal("500").compareTo(account.getCash()));
    }

    @Test
    void addWithExactCashSucceeds() {
        assertTrue(account.add("SBER", 10, new BigDecimal("100")));
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getCash()));
    }

    @Test
    void addFailsWhenNotEnoughCashAndLeavesStateUntouched() {
        assertFalse(account.add("SBER", 11, new BigDecimal("100")));

        assertEquals(0L, account.quantityByTicker("SBER"));
        assertEquals(new BigDecimal("1000"), account.getCash());
    }

    @Test
    void addWithSecurityUsesCurrentPrice() {
        assertTrue(account.add(SBER, 4));

        assertEquals(4L, account.quantityByTicker("SBER"));
        assertEquals(0, new BigDecimal("600").compareTo(account.getCash()));
    }

    @Test
    void subtractSellsPositionAndIncreasesCash() {
        account.add("SBER", 5, new BigDecimal("100"));

        assertTrue(account.subtract("SBER", 2, new BigDecimal("120")));

        assertEquals(3L, account.quantityByTicker("SBER"));
        assertEquals(0, new BigDecimal("740").compareTo(account.getCash()));
    }

    @Test
    void subtractAllSharesSucceeds() {
        account.add("SBER", 5, new BigDecimal("100"));

        assertTrue(account.subtract("SBER", 5, new BigDecimal("100")));
        assertEquals(0L, account.quantityByTicker("SBER"));
    }

    @Test
    void subtractFailsWhenNotEnoughSharesAndLeavesStateUntouched() {
        account.add("SBER", 2, new BigDecimal("100"));

        assertFalse(account.subtract("SBER", 3, new BigDecimal("100")));

        assertEquals(2L, account.quantityByTicker("SBER"));
        assertEquals(0, new BigDecimal("800").compareTo(account.getCash()));
    }

    @Test
    void subtractFailsForUnknownTicker() {
        assertFalse(account.subtract("GAZP", 1, BigDecimal.TEN));
        assertEquals(new BigDecimal("1000"), account.getCash());
    }

    @Test
    void subtractWithSecurityUsesCurrentPrice() {
        account.add(SBER, 4);

        assertTrue(account.subtract(SBER, 1));

        assertEquals(3L, account.quantityByTicker("SBER"));
        assertEquals(0, new BigDecimal("700").compareTo(account.getCash()));
    }

    @Test
    void enoughCashReturnsTrueOnlyWhenCashCoversPrice() {
        assertFalse(account.enoughCash(new BigDecimal("1000.01")));
        assertTrue(account.enoughCash(new BigDecimal("1000")));
        assertTrue(account.enoughCash(new BigDecimal("1")));
    }

    @Test
    void enoughQuantityReturnsTrueOnlyWhenHeldCoversRequested() {
        account.add("SBER", 5, BigDecimal.ONE);

        assertFalse(account.enoughQuantity("SBER", 6));
        assertTrue(account.enoughQuantity("SBER", 5));
        assertTrue(account.enoughQuantity("SBER", 1));
        assertFalse(account.enoughQuantity("GAZP", 1));
    }
}
