package com.example.ticket.auth;

import lombok.Data;

import java.io.Serializable;

/**
 * 放进 HttpSession 的登录者信息。
 *
 * 不带 passwordHash —— 会话里不该有凭据，
 * 哪怕它已经是哈希。
 */
@Data
public class AgentView implements Serializable {

    private Long id;

    private String username;

    private String displayName;

    /** AGENT / LEAD */
    private String role;

    public static AgentView of(Agent agent) {
        AgentView view = new AgentView();
        view.setId(agent.getId());
        view.setUsername(agent.getUsername());
        view.setDisplayName(agent.getDisplayName());
        view.setRole(agent.getRole());
        return view;
    }
}
