package com.example.ledger.staff;

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
 * 初始账号。只在 staff 表为空时执行，不覆盖已有账号。
 *
 * 不把 BCrypt 哈希写进 SQL：那串 $2a$10$... 谁也看不出它对应什么密码，
 * 也没法判断它是不是还对应着某个已知口令。密码来源与算法放在明处。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaffSeeder implements ApplicationRunner {

    private static final List<String[]> SEEDS = List.of(
            new String[]{"caigou", "李采购", "PURCHASER"},
            new String[]{"guanli", "王管理", "MANAGER"}
    );

    private final StaffMapper staffMapper;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.auth.demo-password:123456}")
    private String demoPassword;

    @Override
    public void run(ApplicationArguments args) {
        Long existing = staffMapper.selectCount(Wrappers.<Staff>lambdaQuery());
        if (existing != null && existing > 0) {
            return;
        }
        for (String[] seed : SEEDS) {
            Staff staff = new Staff();
            staff.setUsername(seed[0]);
            staff.setDisplayName(seed[1]);
            staff.setRole(seed[2]);
            staff.setPasswordHash(passwordEncoder.encode(demoPassword));
            staff.setEnabled(true);
            staff.setCreatedAt(LocalDateTime.now());
            staffMapper.insert(staff);
        }
        log.warn("已创建 {} 个演示账号，初始密码取自 app.auth.demo-password。上线前请改掉。",
                SEEDS.size());
    }
}