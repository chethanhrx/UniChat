package com.unichat.dto;

public record AuthResponse(
    String token,
    Long userId,
    String email
) {}
