package com.unichat.controller;

import com.unichat.domain.Connection;
import com.unichat.domain.ModelCache;
import com.unichat.dto.ConnectionRequest;
import com.unichat.dto.ConnectionResponse;
import com.unichat.provider.ModelInfo;
import com.unichat.provider.ProviderClient;
import com.unichat.provider.ProviderClientFactory;
import com.unichat.repository.ConnectionRepository;
import com.unichat.repository.ModelCacheRepository;
import com.unichat.security.KeyVaultService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/connections")
public class ConnectionController {

    private final ConnectionRepository connectionRepository;
    private final ModelCacheRepository modelCacheRepository;
    private final KeyVaultService keyVaultService;
    private final ProviderClientFactory providerClientFactory;

    public ConnectionController(ConnectionRepository connectionRepository,
                                ModelCacheRepository modelCacheRepository,
                                KeyVaultService keyVaultService,
                                ProviderClientFactory providerClientFactory) {
        this.connectionRepository = connectionRepository;
        this.modelCacheRepository = modelCacheRepository;
        this.keyVaultService = keyVaultService;
        this.providerClientFactory = providerClientFactory;
    }

    private ConnectionResponse mapToResponse(Connection connection) {
        String plainKey = keyVaultService.decrypt(connection.getEncryptedApiKey());
        String masked = keyVaultService.maskApiKey(plainKey);
        return new ConnectionResponse(
                connection.getId(),
                connection.getLabel(),
                connection.getBaseUrl(),
                masked,
                connection.getProviderType(),
                connection.getCreatedAt()
        );
    }

    @GetMapping
    public ResponseEntity<List<ConnectionResponse>> listConnections(@RequestAttribute("userId") Long userId) {
        List<ConnectionResponse> list = connectionRepository.findByUserId(userId).stream()
                .map(this::mapToResponse)
                .toList();
        return ResponseEntity.ok(list);
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ConnectionResponse> addConnection(@RequestAttribute("userId") Long userId,
                                                            @Valid @RequestBody ConnectionRequest request) {
        String encryptedKey = keyVaultService.encrypt(request.apiKey());
        Connection tempConnection = Connection.builder()
                .userId(userId)
                .label(request.label())
                .baseUrl(request.baseUrl())
                .encryptedApiKey(encryptedKey)
                .providerType(request.providerType())
                .build();

        ProviderClient client = providerClientFactory.getClient(tempConnection);
        List<ModelInfo> discoveredModels;
        try {
            discoveredModels = client.listModels(tempConnection);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to connect or discover models: " + e.getMessage());
        }

        if (discoveredModels.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No models returned from provider endpoint.");
        }

        Connection saved = connectionRepository.save(tempConnection);

        List<ModelCache> cacheEntries = discoveredModels.stream().map(m -> ModelCache.builder()
                .connectionId(saved.getId())
                .modelId(m.id())
                .displayName(m.displayName())
                .contextWindow(m.contextWindow())
                .lastSyncedAt(LocalDateTime.now())
                .build()).toList();
        modelCacheRepository.saveAll(cacheEntries);

        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(saved));
    }

    @PostMapping("/{id}/refresh")
    @Transactional
    public ResponseEntity<List<ModelCache>> refreshModels(@RequestAttribute("userId") Long userId,
                                                          @PathVariable Long id) {
        Connection connection = connectionRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));

        ProviderClient client = providerClientFactory.getClient(connection);
        List<ModelInfo> discoveredModels;
        try {
            discoveredModels = client.listModels(connection);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to refresh models: " + e.getMessage());
        }

        modelCacheRepository.deleteByConnectionId(connection.getId());

        List<ModelCache> cacheEntries = discoveredModels.stream().map(m -> ModelCache.builder()
                .connectionId(connection.getId())
                .modelId(m.id())
                .displayName(m.displayName())
                .contextWindow(m.contextWindow())
                .lastSyncedAt(LocalDateTime.now())
                .build()).toList();
        cacheEntries = modelCacheRepository.saveAll(cacheEntries);

        return ResponseEntity.ok(cacheEntries);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> deleteConnection(@RequestAttribute("userId") Long userId,
                                                 @PathVariable Long id) {
        Connection connection = connectionRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        modelCacheRepository.deleteByConnectionId(connection.getId());
        connectionRepository.delete(connection);
        return ResponseEntity.noContent().build();
    }
}
