package com.example.ticket.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前登录者。
 *
 * 存在的意义：让「谁在改这张工单」有一个可追溯的来源，
 * 而不是散落在各处的 session 读取代码。
 *
 * getOrNull() 与 require() 分开：
 *     TicketService 在单测里没有请求上下文，用 getOrNull() 才不会炸。
 */
@Component
public class CurrentAgent {

    public AgentView getOrNull() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(AuthService.SESSION_KEY);
        return value instanceof AgentView view ? view : null;
    }

    public String displayNameOrNull() {
        AgentView view = getOrNull();
        return view == null ? null : view.getDisplayName();
    }

    private HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            return servlet.getRequest();
        }
        return null;
    }
}
