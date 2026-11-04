package com.example.ticket.chat;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单条消息。
 *
 * role 用 user / assistant 两个值：
 * 追问话术由代码生成，也算 assistant——它和模型输出在存储上不必区分，
 * 但排查时可以从内容看出是谁写的（追问都是「为了帮您处理，还需要确认…」）。
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private String role;

    private String content;

    private LocalDateTime createdAt;
}
