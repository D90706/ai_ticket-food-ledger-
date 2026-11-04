package com.example.ledger.config;

import com.example.ledger.auth.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截器注册。
 *
 * 保护业务接口，放行登录与文档：
 *   /api/auth/**      登录本身
 *   /v3/api-docs/**   接口文档
 *   /swagger-ui/**    文档页
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    /**
     * 附件静态映射。
     *
     * 注意拦截器只拦 /api/**，所以 /uploads/** 是公开可读的。
     * 这是有意的取舍：图片 URL 带 UUID，猜不出来；
     * 而给图片加鉴权会让 <img> 标签取不到图（浏览器不带 Cookie 之外的凭据），
     * 换来的复杂度不值。正式环境应改为签名 URL。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + uploadDir + "/");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/me",
                        "/api/auth/logout",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html");
    }
}