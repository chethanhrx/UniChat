package com.unichat.domain;

public enum MessageRole {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    private final String value;

    MessageRole(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
