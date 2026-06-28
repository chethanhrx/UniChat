package com.unichat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ConversationRequest(
    @NotNull Long connectionId,
    @NotBlank String modelId,
    @NotBlank String title,
    String systemPrompt
) {}
