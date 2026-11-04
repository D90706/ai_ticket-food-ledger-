package com.example.ticket.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 乐观锁插件。
 *
 * 作用是让 updateById 在实体带 version 时自动变成：
 *     SET ..., version = version + 1 WHERE id = ? AND version = ?
 *
 * 影响行数 0 就说明「读出来之后被人改过」——
 * 这一层是 SQL 级别的兜底，挡的是
 * 「先比较再更新」之间那几毫秒的竞态。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
