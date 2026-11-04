package com.example.ticket.preview.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PreviewRequest {

    @NotBlank(message = "不能为空")
    private String dialogue;
}
