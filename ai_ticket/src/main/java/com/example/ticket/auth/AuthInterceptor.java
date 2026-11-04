package com.example.ticket.auth;

import com.example.ticket.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 人工端的登录拦截。
 *
 * 只拦「人工端专用」的接口：/api/tickets/**、/api/templates/**。
 * 不拦 /api/chat/** —— 顾客端也在用，拦了顾客就聊不了天。
 *
 * 失败时直接写 JSON 而不是抛异常：
 * 这里明确知道该怎么回，没必要绕一层异常解析。
 * 响应体沿用全站约定（HTTP 200 + code=401），
 * 前端只认 body.code，不必区分两套错误通道。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final CurrentAgent currentAgent;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (currentAgent.getOrNull() != null) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.fail(401, "请先登录人工客服工作台")));
        return false;
    }
}
