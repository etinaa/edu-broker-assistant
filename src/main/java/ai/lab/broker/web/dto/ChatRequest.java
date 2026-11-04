package ai.lab.broker.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(@NotBlank String sessionId, @NotBlank @Size(max = 2000) String message) {}
