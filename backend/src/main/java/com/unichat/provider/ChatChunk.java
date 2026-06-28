package com.unichat.provider;

public record ChatChunk(
    String deltaText,
    boolean done,
    String finishReason
) {}
