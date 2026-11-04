package com.example.ticket.auth;

import com.example.ticket.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录相关。/api/auth/** 不经过 AuthInterceptor，否则登录本身就要登录。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CurrentAgent currentAgent;

    @PostMapping("/login")
    public ApiResponse<AgentView> login(@Valid @RequestBody LoginRequest request,
                                        HttpServletRequest httpRequest) {
        AgentView agent = authService.login(request.getUsername(), request.getPassword());
        authService.bind(httpRequest, agent);
        return ApiResponse.ok(agent);
    }

    /** 前端启动时用它判断「有没有登录」，不缓存也不猜 */
    @GetMapping("/me")
    public ApiResponse<AgentView> me() {
        AgentView agent = currentAgent.getOrNull();
        if (agent == null) {
            return ApiResponse.fail(401, "未登录");
        }
        return ApiResponse.ok(agent);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        authService.unbind(request);
        return ApiResponse.ok(null);
    }

    @Data
    public static class LoginRequest {

        @NotBlank(message = "用户名不能为空")
        private String username;

        @NotBlank(message = "密码不能为空")
        private String password;
    }
}
