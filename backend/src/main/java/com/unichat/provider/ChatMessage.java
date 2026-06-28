package com.unichat.provider;

public record ChatMessage(
    String role,
    String content
) {}
