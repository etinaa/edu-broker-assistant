package ai.lab.broker.web;

import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.domain.Account;
import ai.lab.broker.domain.SearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

@RestController
@RequiredArgsConstructor
class AccountController {

    private final BrokerService broker;

    @GetMapping("/accounts/{accountId}")
    Account account(@PathVariable String accountId) {
        return broker.accountById(accountId)
                .orElseThrow(() -> new NoSuchElementException("Account " + accountId + " not found"));
    }

    @GetMapping("/securities/search")
    SearchResult search(@RequestParam String query) {
        return broker.findSecurity(query);
    }
}
