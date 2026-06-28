package com.unichat.provider;

import com.unichat.domain.Connection;
import com.unichat.domain.ProviderType;
import org.springframework.stereotype.Component;

@Component
public class ProviderClientFactory {

    private final OpenAiCompatibleClient openAiClient;
    private final AnthropicClient anthropicClient;
    private final GeminiClient geminiClient;

    public ProviderClientFactory(OpenAiCompatibleClient openAiClient, AnthropicClient anthropicClient, GeminiClient geminiClient) {
        this.openAiClient = openAiClient;
        this.anthropicClient = anthropicClient;
        this.geminiClient = geminiClient;
    }

    public ProviderClient getClient(Connection connection) {
        return getClient(connection.getProviderType());
    }

    public ProviderClient getClient(ProviderType providerType) {
        if (providerType == null) return openAiClient;
        return switch (providerType) {
            case ANTHROPIC -> anthropicClient;
            case GEMINI -> geminiClient;
            default -> openAiClient;
        };
    }
}
