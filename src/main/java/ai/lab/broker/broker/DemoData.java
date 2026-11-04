package ai.lab.broker.broker;

import ai.lab.broker.domain.Account;
import ai.lab.broker.domain.Position;
import ai.lab.broker.domain.Security;

import java.math.BigDecimal;
import java.util.List;

/**
 * Учебные данные из docs/stage1-final-README.md. Все компании, тикеры, ISIN и цены вымышленные
 * (ISIN с корректной контрольной цифрой). Названия подобраны так, чтобы «Training» и «Sample»
 * давали несколько кандидатов.
 */
public final class DemoData {

    public static final String ACCOUNT_ID = "BRK-1";

    public static final List<Security> SECURITIES = List.of(
            new Security("TRNC", "US0000TRNC07", "Training Corp. common stock", new BigDecimal("185.25")),
            new Security("TRNP", "US0000TRNP02", "Training Corp. preferred stock", new BigDecimal("24.10")),
            new Security("TREN", "US0000TREN05", "Training Energy Inc.", new BigDecimal("62.40")),
            new Security("SMPB", "US0000SMPB02", "Sample Bank Holdings", new BigDecimal("41.75")),
            new Security("SMPL", "US0000SMPL00", "Sample Logistics Ltd.", new BigDecimal("12.30")),
            new Security("DEMO", "US0000DEMO07", "Demo Software Inc.", new BigDecimal("410.00")));

    private DemoData() {
    }

    public static Account initialAccount() {
        return new Account(ACCOUNT_ID, new BigDecimal("10000.00"), List.of(
                new Position("TRNC", 20),
                new Position("SMPB", 100),
                new Position("DEMO", 5)));
    }
}
