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
public class OpenAiCompatibleClient implements ProviderClient {

    private final WebClient webClient;
    private final KeyVaultService keyVaultService;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleClient(WebClient.Builder webClientBuilder, KeyVaultService keyVaultService, ObjectMapper objectMapper) {
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
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            List<ModelInfo> models = new ArrayList<>();
            if (responseBody != null) {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode dataNode = root.has("data") ? root.get("data") : (root.has("models") ? root.get("models") : null);
                if (dataNode != null && dataNode.isArray()) {
                    for (JsonNode modelNode : dataNode) {
                        String id = modelNode.has("id") ? modelNode.get("id").asText() : (modelNode.has("name") ? modelNode.get("name").asText() : null);
                        if (id != null && !id.isEmpty()) {
                            int ctx = modelNode.has("context_length") ? modelNode.get("context_length").asInt() : 
                                      (modelNode.has("context_window") ? modelNode.get("context_window").asInt() : 128000);
                            models.add(new ModelInfo(id, id, ctx > 0 ? ctx : null));
                        }
                    }
                }
            }
            return models;
        } catch (Exception e) {
            throw new RuntimeException("Failed to discover models from OpenAI-compatible endpoint: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<ChatChunk> streamChat(Connection connection, ChatRequest request) {
        String apiKey = keyVaultService.decrypt(connection.getEncryptedApiKey());
        String url = normalizeUrl(connection.getBaseUrl(), "/chat/completions");

        Map<String, Object> body = new HashMap<>();
        body.put("model", request.modelId());
        body.put("stream", true);
        if (request.temperature() != null) body.put("temperature", request.temperature());
        if (request.maxTokens() != null) body.put("max_tokens", request.maxTokens());

        List<Map<String, String>> messages = new ArrayList<>();
        for (ChatMessage msg : request.history()) {
            messages.add(Map.of("role", msg.role(), "content", msg.content()));
        }
        body.put("messages", messages);

        return webClient.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .mapNotNull(payload -> {
                    String line = payload.trim();
                    if (line.isEmpty() || line.equals("[DONE]")) {
                        if (line.equals("[DONE]")) {
                            return new ChatChunk("", true, "stop");
                        }
                        return null;
                    }
                    try {
                        JsonNode root = objectMapper.readTree(line);
                        JsonNode choices = root.get("choices");
                        if (choices != null && choices.isArray() && choices.size() > 0) {
                            JsonNode firstChoice = choices.get(0);
                            JsonNode delta = firstChoice.get("delta");
                            String content = "";
                            if (delta != null && delta.has("content") && !delta.get("content").isNull()) {
                                content = delta.get("content").asText();
                            }
                            String finishReason = null;
                            boolean done = false;
                            if (firstChoice.has("finish_reason") && !firstChoice.get("finish_reason").isNull()) {
                                finishReason = firstChoice.get("finish_reason").asText();
                                if (!finishReason.isEmpty() && !"null".equals(finishReason)) {
                                    done = true;
                                }
                            }
                            return new ChatChunk(content, done, finishReason);
                        }
                    } catch (Exception ignored) {
                    }
                    return null;
                });
    }
}
