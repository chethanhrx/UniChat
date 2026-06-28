package com.unichat.dto;

import com.unichat.domain.Conversation;
import com.unichat.domain.Message;
import java.util.List;

public record ConversationDetailResponse(
    Conversation conversation,
    List<Message> messages
) {}
