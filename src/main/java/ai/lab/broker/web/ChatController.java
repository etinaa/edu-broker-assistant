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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.NoSuchElementException;

@RestController
class ChatController {

    static final String ACCOUNT_HEADER = "X-Account-Id";

    private final AssistantService assistant;
    private final BrokerService broker;
    private final ChatStreams streams;

    ChatController(AssistantService assistant, BrokerService broker, ChatStreams streams) {
        this.assistant = assistant;
        this.broker = broker;
        this.streams = streams;
    }

    /**
     * The account comes from the header and is passed to the service; the model knows nothing about it. An unknown
     * account is 404 before the model is called.
     */
    @PostMapping("/chat")
    ChatResponse chat(@RequestHeader(ACCOUNT_HEADER) String accountId, @Valid @RequestBody ChatRequest request) {
        requireAccount(accountId);
        return assistant.chat(accountId, request.sessionId(), request.message());
    }

    /**
     * /chat with progress as server-sent events (see {@link ChatStreams}). The account and the request body are
     * checked first, so an unknown account or an invalid body is still an ordinary 404 or 400.
     */
    @PostMapping("/chat/stream")
    SseEmitter stream(@RequestHeader(ACCOUNT_HEADER) String accountId, @Valid @RequestBody ChatRequest request) {
        requireAccount(accountId);
        return streams.start(accountId, request.sessionId(), request.message());
    }

    /** Start the conversation over: the session history is deleted. */
    @DeleteMapping("/chat/{sessionId}")
    ResponseEntity<Void> resetSession(@RequestHeader(ACCOUNT_HEADER) String accountId,
                                      @PathVariable String sessionId) {
        requireAccount(accountId);
        assistant.resetSession(accountId, sessionId);
        return ResponseEntity.noContent().build();
    }

    private void requireAccount(String accountId) {
        if (!broker.accountExists(accountId)) {
            throw new NoSuchElementException("Account " + accountId + " not found");
        }
    }
}
