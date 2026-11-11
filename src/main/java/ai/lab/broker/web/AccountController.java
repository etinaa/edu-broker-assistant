package ai.lab.broker.web;

import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.domain.Account;
import ai.lab.broker.domain.SearchResult;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Счёт и поиск без LLM — для проверки правил руками и для UI. */
@RestController
class AccountController {

    private final BrokerService broker;

    AccountController(BrokerService broker) {
        this.broker = broker;
    }

    @GetMapping("/account")
    Account account() {
        //return broker.account();
        return null;
    }

    @GetMapping("/securities/search")
    SearchResult search(@RequestParam String query) {
        return broker.findSecurity(query);
    }
}
