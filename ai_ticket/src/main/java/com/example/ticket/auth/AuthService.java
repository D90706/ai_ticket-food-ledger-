package com.example.ticket.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ticket.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录。
 *
 * 三个刻意的选择：
 *
 * 1 用 session，不用 JWT。
 *    单机课程设计没有多实例、没有跨域、没有移动端。
 *    无状态的代价是不能注销（客服在公共电脑上登过就退不掉），
 *    而这些收益我们一个都拿不到。
 *
 * 2 失败信息统一为「用户名或密码不正确」。
 *    分开提示等于告诉攻击者「这个账号存在」。
 *
 * 3 密码只比哈希，不回显、不入会话。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    public static final String SESSION_KEY = "AGENT";

    private final AgentMapper agentMapper;
    private final PasswordEncoder passwordEncoder;

    public AgentView login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new BizException(400, "用户名和密码都不能为空");
        }

        Agent agent = agentMapper.selectOne(
                Wrappers.<Agent>lambdaQuery().eq(Agent::getUsername, username.trim()));

        // 账号不存在与密码错误返回同一句话：不泄露账号是否存在
        if (agent == null || !passwordEncoder.matches(password, agent.getPasswordHash())) {
            log.warn("登录失败：username={}", username);
            throw new BizException(401, "用户名或密码不正确");
        }
        if (Boolean.FALSE.equals(agent.getEnabled())) {
            throw new BizException(401, "该账号已停用");
        }

        log.info("客服 {}（{}）登录", agent.getDisplayName(), agent.getUsername());
        return AgentView.of(agent);
    }

    /** 把登录者放进会话。session id 会随 Set-Cookie 下发 */
    public void bind(HttpServletRequest request, AgentView agent) {
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_KEY, agent);
    }

    public void unbind(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_KEY);
        }
    }
}
