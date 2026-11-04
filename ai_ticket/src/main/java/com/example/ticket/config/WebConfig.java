package com.example.ticket.config;

import com.example.ticket.auth.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：拦截器注册 + 密码哈希。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    /**
     * 只保护人工端的接口。
     *
     * 顾客端要用 /api/chat/**（对话、确认、状态回执），
     * 把它们也拦了，顾客就什么都做不了。
     * 这也是「两端分离」在服务端留下的痕迹。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/tickets/**", "/api/templates/**");
    }

    /**
     * 用 BCrypt，不用 MD5/SHA。
     * BCrypt 自带盐与成本因子，是密码哈希的默认正确选择。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
