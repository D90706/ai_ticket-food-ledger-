package com.example.ticket.auth;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人工客服账号。
 *
 * 只存 BCrypt 哈希，不存明文——这一条没有妥协余地。
 * 初始账号由 AgentSeeder 在启动时补齐，不在 SQL 里写死哈希：
 * 哈希写进 SQL 就成了一个谁也解释不了的魔法字符串。
 */
@Data
@TableName("agent")
public class Agent {

    private Long id;

    private String username;

    /** 显示名。写进工单 assignee 的就是它 */
    private String displayName;

    private String passwordHash;

    /** AGENT / LEAD */
    private String role;

    private Boolean enabled;

    private LocalDateTime createdAt;
}
