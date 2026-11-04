package ai.lab.broker.broker;

import ai.lab.broker.domain.Account;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Единственный счёт BRK-1 в памяти. Счёт определяет сервер, а не модель, поэтому accountId нет
 * ни в аргументах инструментов, ни здесь.
 */
@Component
public class AccountRepository {

    private final AtomicReference<Account> account = new AtomicReference<>();

    public AccountRepository() {
        reset();
    }

    public Account get() {
        return account.get();
    }

    public void save(Account updated) {
        account.set(updated);
    }

    public void reset() {
        account.set(DemoData.initialAccount());
    }
}
