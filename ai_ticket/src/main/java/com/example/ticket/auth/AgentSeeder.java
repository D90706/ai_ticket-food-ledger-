package com.example.ticket.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 初始客服账号。
 *
 * 为什么不在 schema.sql 里写死 BCrypt 哈希：
 * 那串 $2a$10$... 对读代码的人是不可解释的——没人能看出密码是什么，
 * 也没法判断它是不是还对应着某个已知口令。
 * 用代码生成，密码来源（配置项）和哈希算法都在明处。
 *
 * 只在 agent 表为空时执行，不覆盖已有账号。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSeeder implements ApplicationRunner {

    private static final List<String[]> SEEDS = List.of(
            new String[]{"zhangsan", "张三", "AGENT"},
            new String[]{"lisi", "李四", "LEAD"}
    );

    private final AgentMapper agentMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.auth.demo-password:123456}")
    private String demoPassword;

    @Override
    public void run(ApplicationArguments args) {
        Long existing = agentMapper.selectCount(Wrappers.<Agent>lambdaQuery());
        if (existing != null && existing > 0) {
            return;
        }

        for (String[] seed : SEEDS) {
            Agent agent = new Agent();
            agent.setUsername(seed[0]);
            agent.setDisplayName(seed[1]);
            agent.setRole(seed[2]);
            agent.setPasswordHash(passwordEncoder.encode(demoPassword));
            agent.setEnabled(true);
            agent.setCreatedAt(LocalDateTime.now());
            agentMapper.insert(agent);
        }
        log.warn("已创建 {} 个演示客服账号，初始密码取自 app.auth.demo-password。"
                + "上线前请改掉。", SEEDS.size());
    }
}
