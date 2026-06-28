package com.unichat.dto;

import jakarta.validation.constraints.NotBlank;

public record MessageRequest(
    @NotBlank String content,
    Double temperature,
    Integer maxTokens
) {}
