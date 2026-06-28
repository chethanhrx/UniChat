package com.unichat.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unichat.domain.Connection;
import com.unichat.security.KeyVaultService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class AnthropicClient implements ProviderClient {

    private final WebClient webClient;
    private final KeyVaultService keyVaultService;
    private final ObjectMapper objectMapper;

    public AnthropicClient(WebClient.Builder webClientBuilder, KeyVaultService keyVaultService, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.keyVaultService = keyVaultService;
        this.objectMapper = objectMapper;
    }

    private String normalizeUrl(String baseUrl, String endpoint) {
        String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return cleanBase + endpoint;
    }

    @Override
    public List<ModelInfo> listModels(Connection connection) {
        String apiKey = keyVaultService.decrypt(connection.getEncryptedApiKey());
        String url = normalizeUrl(connection.getBaseUrl(), "/models");

        try {
            String responseBody = webClient.get()
                    .uri(url)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            List<ModelInfo> models = new ArrayList<>();
            if (responseBody != null) {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode dataNode = root.get("data");
                if (dataNode != null && dataNode.isArray()) {
                    for (JsonNode modelNode : dataNode) {
                        String id = modelNode.has("id") ? modelNode.get("id").asText() : null;
                        String displayName = modelNode.has("display_name") ? modelNode.get("display_name").asText() : id;
                        if (id != null) {
                            models.add(new ModelInfo(id, displayName, 200000));
                        }
                    }
                }
            }
            if (models.isEmpty()) {
                // Fallback standard Claude models if endpoint doesn't support listing
                models.add(new ModelInfo("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", 200000));
                models.add(new ModelInfo("claude-3-5-haiku-20241022", "Claude 3.5 Haiku", 200000));
                models.add(new ModelInfo("claude-3-opus-20240229", "Claude 3 Opus", 200000));
            }
            return models;
        } catch (Exception e) {
            // If models endpoint fails, return known defaults
            return List.of(
                new ModelInfo("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", 200000),
                new ModelInfo("claude-3-5-haiku-20241022", "Claude 3.5 Haiku", 200000),
                new ModelInfo("claude-3-opus-20240229", "Claude 3 Opus", 200000)
            );
        }
    }

    @Override
    public Flux<ChatChunk> streamChat(Connection connection, ChatRequest request) {
        String apiKey = keyVaultService.decrypt(connection.getEncryptedApiKey());
        String url = normalizeUrl(connection.getBaseUrl(), "/messages");

        Map<String, Object> body = new HashMap<>();
        body.put("model", request.modelId());
        body.put("stream", true);
        body.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : 4096);
        if (request.temperature() != null) body.put("temperature", request.temperature());

        StringBuilder systemPrompt = new StringBuilder();
        List<Map<String, String>> messages = new ArrayList<>();
        for (ChatMessage msg : request.history()) {
            if ("system".equalsIgnoreCase(msg.role())) {
                if (!systemPrompt.isEmpty()) systemPrompt.append("\n\n");
                systemPrompt.append(msg.content());
            } else {
                messages.add(Map.of("role", msg.role(), "content", msg.content()));
            }
        }
        if (!systemPrompt.isEmpty()) {
            body.put("system", systemPrompt.toString());
        }
        body.put("messages", messages);

        return webClient.post()
                .uri(url)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .mapNotNull(payload -> {
                    String line = payload.trim();
                    if (line.isEmpty()) return null;
                    try {
                        JsonNode root = objectMapper.readTree(line);
                        String type = root.has("type") ? root.get("type").asText() : "";
                        if ("content_block_delta".equals(type)) {
                            JsonNode delta = root.get("delta");
                            if (delta != null && "text_delta".equals(delta.get("type").asText())) {
                                String text = delta.get("text").asText();
                                return new ChatChunk(text, false, null);
                            }
                        } else if ("message_delta".equals(type)) {
                            JsonNode delta = root.get("delta");
                            String stopReason = delta != null && delta.has("stop_reason") ? delta.get("stop_reason").asText() : "end_turn";
                            return new ChatChunk("", true, stopReason);
                        } else if ("message_stop".equals(type)) {
                            return new ChatChunk("", true, "stop");
                        }
                    } catch (Exception ignored) {
                    }
                    return null;
                });
    }
}
