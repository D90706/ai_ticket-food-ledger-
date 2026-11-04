package com.example.ticket.chat.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessageView {

    private Long id;
    private String role;
    private String content;
    private LocalDateTime createdAt;

    public static MessageView of(Long id, String role, String content) {
        MessageView v = new MessageView();
        v.id = id;
        v.role = role;
        v.content = content;
        return v;
    }
}
