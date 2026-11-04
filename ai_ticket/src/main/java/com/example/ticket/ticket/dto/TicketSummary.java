package com.example.ticket.ticket.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TicketSummary {

    private Long id;
    private Long templateId;
    private String qualityLevel;
    private String status;
    private String assignee;
    private String summary;
    private Boolean needsClarify;
    private LocalDateTime createdAt;
}
