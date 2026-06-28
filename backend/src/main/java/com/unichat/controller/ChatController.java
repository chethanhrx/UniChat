package com.unichat.controller;

import com.unichat.domain.*;
import com.unichat.dto.ConversationDetailResponse;
import com.unichat.dto.ConversationRequest;
import com.unichat.dto.MessageRequest;
import com.unichat.provider.*;
import com.unichat.repository.ConnectionRepository;
import com.unichat.repository.ConversationRepository;
import com.unichat.repository.MessageRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/conversations")
public class ChatController {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ConnectionRepository connectionRepository;
    private final ProviderClientFactory providerClientFactory;

    public ChatController(ConversationRepository conversationRepository,
                          MessageRepository messageRepository,
                          ConnectionRepository connectionRepository,
                          ProviderClientFactory providerClientFactory) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.connectionRepository = connectionRepository;
        this.providerClientFactory = providerClientFactory;
    }

    @GetMapping
    public ResponseEntity<List<Conversation>> listConversations(@RequestAttribute("userId") Long userId) {
        return ResponseEntity.ok(conversationRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Conversation> createConversation(@RequestAttribute("userId") Long userId,
                                                           @Valid @RequestBody ConversationRequest request) {
        Connection connection = connectionRepository.findByIdAndUserId(request.connectionId(), userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid connection ID"));

        Conversation conv = Conversation.builder()
                .userId(userId)
                .connectionId(connection.getId())
                .modelId(request.modelId())
                .title(request.title())
                .systemPrompt(request.systemPrompt())
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(conversationRepository.save(conv));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConversationDetailResponse> getConversation(@RequestAttribute("userId") Long userId,
                                                                      @PathVariable Long id) {
        Conversation conv = conversationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conv.getId());
        return ResponseEntity.ok(new ConversationDetailResponse(conv, messages));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> deleteConversation(@RequestAttribute("userId") Long userId,
                                                   @PathVariable Long id) {
        Conversation conv = conversationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        messageRepository.deleteByConversationId(conv.getId());
        conversationRepository.delete(conv);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatChunk> streamChat(@RequestAttribute("userId") Long userId,
                                      @PathVariable Long id,
                                      @Valid @RequestBody MessageRequest request) {
        Conversation conv = conversationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));

        Connection connection = connectionRepository.findByIdAndUserId(conv.getConnectionId(), userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Associated connection not found"));

        // Persist User Message
        Message userMsg = Message.builder()
                .conversationId(conv.getId())
                .role(MessageRole.USER)
                .content(request.content())
                .createdAt(LocalDateTime.now())
                .build();
        messageRepository.save(userMsg);

        // Build History
        List<Message> existingMessages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conv.getId());
        List<ChatMessage> history = new ArrayList<>();
        if (conv.getSystemPrompt() != null && !conv.getSystemPrompt().trim().isEmpty()) {
            history.add(new ChatMessage("system", conv.getSystemPrompt().trim()));
        }
        for (Message m : existingMessages) {
            history.add(new ChatMessage(m.getRole().getValue(), m.getContent()));
        }

        ChatRequest chatRequest = new ChatRequest(
                conv.getModelId(),
                history,
                request.temperature(),
                request.maxTokens()
        );

        ProviderClient client = providerClientFactory.getClient(connection);

        StringBuilder assistantContent = new StringBuilder();
        AtomicBoolean saved = new AtomicBoolean(false);

        Runnable saveAssistantMsg = () -> {
            if (saved.compareAndSet(false, true) && !assistantContent.isEmpty()) {
                Message assistantMsg = Message.builder()
                        .conversationId(conv.getId())
                        .role(MessageRole.ASSISTANT)
                        .content(assistantContent.toString())
                        .createdAt(LocalDateTime.now())
                        .build();
                messageRepository.save(assistantMsg);
            }
        };

        return client.streamChat(connection, chatRequest)
                .doOnNext(chunk -> {
                    if (chunk.deltaText() != null) {
                        assistantContent.append(chunk.deltaText());
                    }
                    if (chunk.done()) {
                        saveAssistantMsg.run();
                    }
                })
                .doOnComplete(saveAssistantMsg::run)
                .doOnError(err -> {
                    if (!assistantContent.isEmpty()) {
                        assistantContent.append("\n\n[Error during streaming: ").append(err.getMessage()).append("]");
                        saveAssistantMsg.run();
                    }
                });
    }
}
