package com.unichat.provider;

import com.unichat.domain.Connection;
import reactor.core.publisher.Flux;
import java.util.List;

public interface ProviderClient {
    List<ModelInfo> listModels(Connection connection);
    Flux<ChatChunk> streamChat(Connection connection, ChatRequest request);
}
