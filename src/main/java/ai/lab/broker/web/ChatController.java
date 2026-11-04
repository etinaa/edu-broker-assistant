package ai.lab.broker.web;

import ai.lab.broker.agent.AssistantService;
import ai.lab.broker.web.dto.ChatRequest;
import ai.lab.broker.web.dto.ChatResponse;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ChatController {

    private final AssistantService assistant;

    ChatController(AssistantService assistant) {
        this.assistant = assistant;
    }

    @PostMapping("/chat")
    ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return assistant.chat(request.sessionId(), request.message());
    }

    /** Начать диалог заново: история сессии удаляется. */
    @DeleteMapping("/chat/{sessionId}")
    ResponseEntity<Void> resetSession(@PathVariable String sessionId) {
        assistant.resetSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}
