package ai.lab.broker.broker;

import ai.lab.broker.domain.Account;

import org.apache.commons.lang3.Strings;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory accounts, seeded with the demo accounts BRK-1 and BRK-2 on startup. Lookup by id is case-sensitive. */
@Component
public class AccountRepository {

    private final List<Account> accounts;

    public AccountRepository() {
        accounts = new CopyOnWriteArrayList<>();

        Account account1 = new Account("BRK-1", new BigDecimal("10000.00"),
                Map.of("TRNC", 20L, "SMPB", 100L, "DEMO", 5L)
        );
        accounts.add(account1);

        Account account2 = new Account("BRK-2", new BigDecimal("100.00"),
                Map.of("SMPL", 10L)
        );
        accounts.add(account2);
    }

    public List<Account> all() {
        return List.copyOf(accounts);
    }

    public Optional<Account> byId(String id) {
        return accounts.stream()
                .filter(account -> Strings.CS.equals(account.getId(), id))
                .findFirst();
    }
}
