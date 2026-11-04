package com.example.ledger.auth;

import com.example.ledger.common.ApiResponse;
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

/** /api/auth/** 不经过拦截器，否则登录本身就要登录。 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CurrentStaff currentStaff;

    @PostMapping("/login")
    public ApiResponse<StaffView> login(@Valid @RequestBody LoginRequest request,
                                        HttpServletRequest httpRequest) {
        StaffView staff = authService.login(request.getUsername(), request.getPassword());
        authService.bind(httpRequest, staff);
        return ApiResponse.ok(staff);
    }

    @GetMapping("/me")
    public ApiResponse<StaffView> me() {
        StaffView staff = currentStaff.getOrNull();
        return staff == null ? ApiResponse.fail(401, "未登录") : ApiResponse.ok(staff);
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