package com.unichat.dto;

import com.unichat.domain.ProviderType;
import java.time.LocalDateTime;

public record ConnectionResponse(
    Long id,
    String label,
    String baseUrl,
    String maskedApiKey,
    ProviderType providerType,
    LocalDateTime createdAt
) {}
