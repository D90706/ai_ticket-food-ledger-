package com.example.ledger.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ledger.common.BizException;
import com.example.ledger.staff.Staff;
import com.example.ledger.staff.StaffMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录。
 *
 * 用 session 不用 JWT：单机部署没有多实例与跨域需求，
 * 而 JWT 的代价是不能真正注销——采购员在食堂公用电脑上登过就退不掉。
 *
 * 失败提示统一为「用户名或密码不正确」：
 * 分开提示等于告诉别人「这个账号存在」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    public static final String SESSION_KEY = "STAFF";

    private final StaffMapper staffMapper;
    private final PasswordEncoder passwordEncoder;

    public StaffView login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new BizException(400, "用户名和密码都不能为空");
        }
        Staff staff = staffMapper.selectOne(
                Wrappers.<Staff>lambdaQuery().eq(Staff::getUsername, username.trim()));

        if (staff == null || !passwordEncoder.matches(password, staff.getPasswordHash())) {
            log.warn("登录失败 username={}", username);
            throw new BizException(401, "用户名或密码不正确");
        }
        if (Boolean.FALSE.equals(staff.getEnabled())) {
            throw new BizException(401, "该账号已停用");
        }
        log.info("登录成功 {}（{}）", staff.getDisplayName(), staff.getRole());
        return StaffView.of(staff);
    }

    public void bind(HttpServletRequest request, StaffView staff) {
        request.getSession(true).setAttribute(SESSION_KEY, staff);
    }

    public void unbind(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_KEY);
        }
    }
}