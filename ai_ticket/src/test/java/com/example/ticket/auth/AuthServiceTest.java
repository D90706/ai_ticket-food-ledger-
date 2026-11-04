package com.example.ticket.auth;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.ticket.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 登录的单元测试。
 *
 * 守三件事：
 *   1 密码只比 BCrypt 哈希，绝不明文比较
 *   2 账号不存在与密码错误返回同一句话（不泄露账号是否存在）
 *   3 停用账号登不进来
 */
class AuthServiceTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final AgentMapper agentMapper = mock(AgentMapper.class);

    private AuthService service;
    private Agent zhangsan;

    @BeforeEach
    void setUp() {
        service = new AuthService(agentMapper, encoder);

        zhangsan = new Agent();
        zhangsan.setId(1L);
        zhangsan.setUsername("zhangsan");
        zhangsan.setDisplayName("张三");
        zhangsan.setRole("AGENT");
        zhangsan.setEnabled(true);
        zhangsan.setPasswordHash(encoder.encode("123456"));

        when(agentMapper.selectOne(any(Wrapper.class))).thenReturn(zhangsan);
    }

    @Test
    @DisplayName("正确的账号密码 → 拿到登录者信息，且不含密码哈希")
    void loginOk() {
        AgentView view = service.login("zhangsan", "123456");

        assertEquals("张三", view.getDisplayName());
        assertEquals("AGENT", view.getRole());
        // AgentView 里根本没有 passwordHash 字段，这是编译期就成立的边界
    }

    @Test
    @DisplayName("密码错误 → 401，提示不区分「账号不存在」")
    void wrongPassword() {
        BizException e = assertThrows(BizException.class,
                () -> service.login("zhangsan", "wrong"));
        assertEquals(401, e.getCode());
        assertEquals("用户名或密码不正确", e.getMessage());
    }

    @Test
    @DisplayName("账号不存在 → 同样的提示，不泄露账号是否存在")
    void unknownUser() {
        when(agentMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        BizException e = assertThrows(BizException.class,
                () -> service.login("nobody", "123456"));
        assertEquals(401, e.getCode());
        assertEquals("用户名或密码不正确", e.getMessage());
    }

    @Test
    @DisplayName("停用账号登不进来")
    void disabledAgent() {
        zhangsan.setEnabled(false);

        BizException e = assertThrows(BizException.class,
                () -> service.login("zhangsan", "123456"));
        assertEquals(401, e.getCode());
        assertEquals("该账号已停用", e.getMessage());
    }

    @Test
    @DisplayName("空用户名/空密码在比较之前就被拦下")
    void blankInput() {
        assertEquals(400, assertThrows(BizException.class,
                () -> service.login(" ", "123456")).getCode());
        assertEquals(400, assertThrows(BizException.class,
                () -> service.login("zhangsan", "")).getCode());
    }
}
