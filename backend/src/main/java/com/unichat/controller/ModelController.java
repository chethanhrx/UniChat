package com.unichat.controller;

import com.unichat.domain.Connection;
import com.unichat.domain.ModelCache;
import com.unichat.repository.ConnectionRepository;
import com.unichat.repository.ModelCacheRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/connections/{connectionId}/models")
public class ModelController {

    private final ConnectionRepository connectionRepository;
    private final ModelCacheRepository modelCacheRepository;

    public ModelController(ConnectionRepository connectionRepository, ModelCacheRepository modelCacheRepository) {
        this.connectionRepository = connectionRepository;
        this.modelCacheRepository = modelCacheRepository;
    }

    @GetMapping
    public ResponseEntity<List<ModelCache>> getModels(@RequestAttribute("userId") Long userId,
                                                      @PathVariable Long connectionId) {
        Connection connection = connectionRepository.findByIdAndUserId(connectionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        return ResponseEntity.ok(modelCacheRepository.findByConnectionId(connection.getId()));
    }
}
