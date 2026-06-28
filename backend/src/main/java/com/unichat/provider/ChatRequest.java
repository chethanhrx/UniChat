package com.unichat.provider;

import java.util.List;

public record ChatRequest(
    String modelId,
    List<ChatMessage> history,
    Double temperature,
    Integer maxTokens
) {}
