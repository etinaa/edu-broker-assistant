package ai.lab.broker.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** sessionId is chosen by the client, so it is bounded: it is a key in memory and appears in logs and traces. */
public record ChatRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String sessionId,
        @NotBlank @Size(max = 2000) String message) {}
