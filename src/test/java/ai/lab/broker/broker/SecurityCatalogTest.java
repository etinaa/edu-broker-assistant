package ai.lab.broker.broker;

import ai.lab.broker.domain.Security;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SecurityCatalogTest {

    private SecurityCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new SecurityCatalog();
    }

    @Test
    void allReturnsAllDemoSecurities() {
        assertEquals(SecurityCatalog.DEMO_SECURITIES, catalog.all());
        assertEquals(6, catalog.all().size());
    }

    @Test
    void allReturnsUnmodifiableSnapshot() {
        List<Security> all = catalog.all();

        assertThrows(UnsupportedOperationException.class,
                () -> all.add(new Security("X", "Y", "Z", BigDecimal.ONE)));
        assertEquals(6, catalog.all().size());
    }

    @Test
    void tickersAndIsinsAreUnique() {
        assertEquals(6, catalog.all().stream().map(Security::ticker).distinct().count());
        assertEquals(6, catalog.all().stream().map(Security::isin).distinct().count());
    }

    @Test
    void byTickerFindsSecurity() {
        Security s = catalog.byTicker("TRNC").orElseThrow();

        assertEquals("US0000TRNC07", s.isin());
        assertEquals("Training Corp. common stock", s.name());
        assertEquals(0, new BigDecimal("185.25").compareTo(s.currentPrice()));
    }

    @Test
    void byTickerIsCaseInsensitive() {
        assertEquals("DEMO", catalog.byTicker("demo").orElseThrow().ticker());
        assertEquals("DEMO", catalog.byTicker("DeMo").orElseThrow().ticker());
    }

    @Test
    void byTickerReturnsEmptyForUnknownEmptyAndNull() {
        assertTrue(catalog.byTicker("NOPE").isEmpty());
        assertTrue(catalog.byTicker("").isEmpty());
        assertTrue(catalog.byTicker(null).isEmpty());
    }

    @Test
    void byTickerRequiresFullMatch() {
        assertTrue(catalog.byTicker("TRN").isEmpty());
        assertTrue(catalog.byTicker("TRNC ").isEmpty());
    }

    @Test
    void byIsinFindsSecurity() {
        Security s = catalog.byIsin("US0000SMPL00").orElseThrow();

        assertEquals("SMPL", s.ticker());
        assertEquals(0, new BigDecimal("12.30").compareTo(s.currentPrice()));
    }

    @Test
    void byIsinIsCaseInsensitive() {
        assertEquals("SMPL", catalog.byIsin("us0000smpl00").orElseThrow().ticker());
    }

    @Test
    void byIsinReturnsEmptyForUnknownEmptyAndNull() {
        assertTrue(catalog.byIsin("US0000NOPE00").isEmpty());
        assertTrue(catalog.byIsin("").isEmpty());
        assertTrue(catalog.byIsin(null).isEmpty());
    }

    @Test
    void byIsinDoesNotMatchTicker() {
        assertTrue(catalog.byIsin("TRNC").isEmpty());
    }

    @Test
    void everyDemoSecurityIsFoundByItsTickerAndIsin() {
        for (Security s : SecurityCatalog.DEMO_SECURITIES) {
            assertEquals(s, catalog.byTicker(s.ticker()).orElseThrow());
            assertEquals(s, catalog.byIsin(s.isin()).orElseThrow());
        }
    }

    @Test
    void allDemoPricesArePositive() {
        for (Security s : catalog.all()) {
            assertTrue(s.currentPrice().signum() > 0, s.ticker());
        }
    }
}
