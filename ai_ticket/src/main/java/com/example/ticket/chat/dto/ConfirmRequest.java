package com.example.ticket.chat.dto;

import lombok.Data;

/**
 * 顾客确认提交。
 *
 * summary 可选：门店/顾客改过的那句话。
 * 不传（或传空）就用系统整理好的那一版——
 * 「确认」这个动作本身仍然有意义（顾客看过并认可），
 * 只是他选择不改。
 */
@Data
public class ConfirmRequest {

    private String summary;
}
