package com.example.ticket.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 拦截器的单元测试：不启动 Spring，直接调 preHandle。
 *
 * 守两件事：
 *   1 没登录 → 拦住，并返回全站统一的响应体（code=401）
 *   2 已登录 → 放行
 */
class AuthInterceptorTest {

    private final CurrentAgent currentAgent = mock(CurrentAgent.class);
    private final AuthInterceptor interceptor =
            new AuthInterceptor(currentAgent, new ObjectMapper());

    @Test
    @DisplayName("未登录：拦住请求，响应体带 code=401 和一句人话")
    void blocksAnonymous() throws Exception {
        when(currentAgent.getOrNull()).thenReturn(null);

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean pass = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

        assertFalse(pass);
        String body = response.getContentAsString();
        assertTrue(body.contains("401"), body);
        assertTrue(body.contains("请先登录"), body);
    }

    @Test
    @DisplayName("已登录：放行")
    void passesAuthenticated() throws Exception {
        AgentView agent = new AgentView();
        agent.setDisplayName("张三");
        when(currentAgent.getOrNull()).thenReturn(agent);

        boolean pass = interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object());

        assertTrue(pass);
    }
}
