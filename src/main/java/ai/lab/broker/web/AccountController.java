package ai.lab.broker.web;

import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.domain.Account;
import ai.lab.broker.domain.SearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

@RestController
@RequiredArgsConstructor
class AccountController {

    private final BrokerService broker;

    /**
     * Only the account named in X-Account-Id can be read. Another account is 404, the same as a missing one,
     * so account ids cannot be probed.
     */
    @GetMapping("/accounts/{accountId}")
    Account account(@RequestHeader(ChatController.ACCOUNT_HEADER) String callerAccountId,
                    @PathVariable String accountId) {
        return broker.accountById(accountId)
                .filter(account -> account.getId().equals(callerAccountId))
                .orElseThrow(() -> new NoSuchElementException("Account " + accountId + " not found"));
    }

    @GetMapping("/securities/search")
    SearchResult search(@RequestParam String query) {
        return broker.findSecurity(query);
    }
}
