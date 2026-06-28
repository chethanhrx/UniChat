package com.unichat.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unichat.domain.Connection;
import com.unichat.security.KeyVaultService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class GeminiClient implements ProviderClient {

    private final WebClient webClient;
    private final KeyVaultService keyVaultService;
    private final ObjectMapper objectMapper;

    public GeminiClient(WebClient.Builder webClientBuilder, KeyVaultService keyVaultService, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.keyVaultService = keyVaultService;
        this.objectMapper = objectMapper;
    }

    private String normalizeBaseUrl(String baseUrl) {
        String clean = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (!clean.contains("/v1")) {
            clean += "/v1beta";
        }
        return clean;
    }

    @Override
    public List<ModelInfo> listModels(Connection connection) {
        String apiKey = keyVaultService.decrypt(connection.getEncryptedApiKey());
        String url = normalizeBaseUrl(connection.getBaseUrl()) + "/models?key=" + apiKey;

        try {
            String responseBody = webClient.get()
                    .uri(url)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            List<ModelInfo> models = new ArrayList<>();
            if (responseBody != null) {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode modelsNode = root.get("models");
                if (modelsNode != null && modelsNode.isArray()) {
                    for (JsonNode modelNode : modelsNode) {
                        String name = modelNode.has("name") ? modelNode.get("name").asText() : null;
                        if (name != null && name.contains("gemini")) {
                            String id = name.startsWith("models/") ? name.substring(7) : name;
                            String displayName = modelNode.has("displayName") ? modelNode.get("displayName").asText() : id;
                            int ctx = modelNode.has("inputTokenLimit") ? modelNode.get("inputTokenLimit").asInt() : 1048576;
                            models.add(new ModelInfo(id, displayName, ctx));
                        }
                    }
                }
            }
            if (models.isEmpty()) {
                models.add(new ModelInfo("gemini-1.5-pro", "Gemini 1.5 Pro", 2097152));
                models.add(new ModelInfo("gemini-1.5-flash", "Gemini 1.5 Flash", 1048576));
            }
            return models;
        } catch (Exception e) {
            return List.of(
                new ModelInfo("gemini-1.5-pro", "Gemini 1.5 Pro", 2097152),
                new ModelInfo("gemini-1.5-flash", "Gemini 1.5 Flash", 1048576)
            );
        }
    }

    @Override
    public Flux<ChatChunk> streamChat(Connection connection, ChatRequest request) {
        String apiKey = keyVaultService.decrypt(connection.getEncryptedApiKey());
        String modelId = request.modelId().startsWith("models/") ? request.modelId() : "models/" + request.modelId();
        String url = normalizeBaseUrl(connection.getBaseUrl()) + "/" + modelId + ":streamGenerateContent?alt=sse&key=" + apiKey;

        Map<String, Object> body = new HashMap<>();
        
        Map<String, Object> genConfig = new HashMap<>();
        if (request.temperature() != null) genConfig.put("temperature", request.temperature());
        if (request.maxTokens() != null) genConfig.put("maxOutputTokens", request.maxTokens());
        if (!genConfig.isEmpty()) body.put("generationConfig", genConfig);

        List<Map<String, Object>> contents = new ArrayList<>();
        StringBuilder systemInstruction = new StringBuilder();

        for (ChatMessage msg : request.history()) {
            if ("system".equalsIgnoreCase(msg.role())) {
                if (!systemInstruction.isEmpty()) systemInstruction.append("\n");
                systemInstruction.append(msg.content());
            } else {
                String role = "user".equalsIgnoreCase(msg.role()) ? "user" : "model";
                contents.add(Map.of("role", role, "parts", List.of(Map.of("text", msg.content()))));
            }
        }
        if (!systemInstruction.isEmpty()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction.toString()))));
        }
        body.put("contents", contents);

        return webClient.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .mapNotNull(payload -> {
                    String line = payload.trim();
                    if (line.isEmpty()) return null;
                    try {
                        JsonNode root = objectMapper.readTree(line);
                        JsonNode candidates = root.get("candidates");
                        if (candidates != null && candidates.isArray() && candidates.size() > 0) {
                            JsonNode cand = candidates.get(0);
                            String text = "";
                            JsonNode content = cand.get("content");
                            if (content != null && content.has("parts")) {
                                JsonNode parts = content.get("parts");
                                if (parts.isArray() && parts.size() > 0) {
                                    text = parts.get(0).has("text") ? parts.get(0).get("text").asText() : "";
                                }
                            }
                            String finishReason = cand.has("finishReason") && !cand.get("finishReason").isNull() ? cand.get("finishReason").asText() : null;
                            boolean done = finishReason != null && !finishReason.isEmpty() && !"STOP".equalsIgnoreCase(finishReason) && !"null".equalsIgnoreCase(finishReason);
                            if ("STOP".equalsIgnoreCase(finishReason)) done = true;
                            return new ChatChunk(text, done, finishReason);
                        }
                    } catch (Exception ignored) {
                    }
                    return null;
                });
    }
}
