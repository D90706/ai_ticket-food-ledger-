package com.example.ticket.chat.dto;

import lombok.Data;

@Data
public class CreateSessionResponse {

    private Long sessionId;
    private String templateName;
    private String greeting;
}
