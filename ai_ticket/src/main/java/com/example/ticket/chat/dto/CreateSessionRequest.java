package com.example.ticket.chat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateSessionRequest {

    @NotNull(message = "不能为空")
    private Long templateId;
}
