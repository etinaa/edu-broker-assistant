package ai.lab.broker.web;

import ai.lab.broker.agent.AssistantService;
import ai.lab.broker.broker.BrokerService;
import ai.lab.broker.web.dto.ChatRequest;
import ai.lab.broker.web.dto.ChatResponse;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

@RestController
class ChatController {

    static final String ACCOUNT_HEADER = "X-Account-Id";

    private final AssistantService assistant;
    private final BrokerService broker;

    ChatController(AssistantService assistant, BrokerService broker) {
        this.assistant = assistant;
        this.broker = broker;
    }

    /**
     * Счёт берётся из заголовка и передаётся сервису; модель о нём не знает. Несуществующий счёт — 404
     * до обращения к модели.
     */
    @PostMapping("/chat")
    ChatResponse chat(@RequestHeader(ACCOUNT_HEADER) String accountId, @Valid @RequestBody ChatRequest request) {
        requireAccount(accountId);
        return assistant.chat(accountId, request.sessionId(), request.message());
    }

    /** Начать диалог заново: история сессии удаляется. */
    @DeleteMapping("/chat/{sessionId}")
    ResponseEntity<Void> resetSession(@RequestHeader(ACCOUNT_HEADER) String accountId,
                                      @PathVariable String sessionId) {
        requireAccount(accountId);
        assistant.resetSession(accountId, sessionId);
        return ResponseEntity.noContent().build();
    }

    private void requireAccount(String accountId) {
        if (broker.accountById(accountId).isEmpty()) {
            throw new NoSuchElementException("Account " + accountId + " not found");
        }
    }
}
