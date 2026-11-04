package ai.lab.broker.broker;

import ai.lab.broker.domain.Account;
import ai.lab.broker.domain.Security;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Учебные данные совпадают с README: от них зависят ожидаемые ответы сценариев. */
class DemoDataTest {

    @Test
    void accountMatchesReadme() {
        Account account = DemoData.initialAccount();

        assertThat(account.cash()).isEqualByComparingTo("10000.00");
        assertThat(account.quantityOf("TRNC")).isEqualTo(20);
        assertThat(account.quantityOf("smpb")).isEqualTo(100);
        assertThat(account.quantityOf("DEMO")).isEqualTo(5);
        assertThat(account.quantityOf("TREN")).isZero();
    }

    @Test
    void catalogHasSixSecuritiesWithValidIsins() {
        assertThat(DemoData.SECURITIES).hasSize(6);
        assertThat(DemoData.SECURITIES).allSatisfy(s -> assertThat(isValidIsin(s.isin())).as(s.isin()).isTrue());
    }

    @Test
    void repositoriesResetToDemoData() {
        SecurityCatalog catalog = new SecurityCatalog();
        catalog.updatePrice("DEMO", new BigDecimal("999.00"));
        catalog.reset();

        assertThat(catalog.byTicker("demo")).map(Security::price).hasValueSatisfying(
                price -> assertThat(price).isEqualByComparingTo("410.00"));
    }

    /** Контрольная цифра ISIN: буквы → числа (A=10…Z=35), затем алгоритм Луна. */
    private static boolean isValidIsin(String isin) {
        if (!isin.matches("[A-Z]{2}[A-Z0-9]{9}\\d")) {
            return false;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : isin.toCharArray()) {
            digits.append(Character.digit(c, 36));
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (doubleIt) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
