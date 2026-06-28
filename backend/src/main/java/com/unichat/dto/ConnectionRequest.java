package com.unichat.dto;

import com.unichat.domain.ProviderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ConnectionRequest(
    @NotBlank String label,
    @NotBlank String baseUrl,
    @NotBlank String apiKey,
    @NotNull ProviderType providerType
) {}
