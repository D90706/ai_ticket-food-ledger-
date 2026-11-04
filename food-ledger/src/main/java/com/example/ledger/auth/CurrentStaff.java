package com.example.ledger.auth;

import com.example.ledger.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 当前登录者。
 *
 * 三种问法，别混：
 *   getOrNull()      可能没人（比如单元测试里没有请求上下文）
 *   require()        没登录就 401
 *   requireManager() 不是管理员就 403
 */
@Component
public class CurrentStaff {

    public StaffView getOrNull() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(AuthService.SESSION_KEY);
        return value instanceof StaffView v ? v : null;
    }

    public StaffView require() {
        StaffView staff = getOrNull();
        if (staff == null) {
            throw new BizException(401, "请先登录");
        }
        return staff;
    }

    public StaffView requireManager() {
        StaffView staff = require();
        if (!staff.isManager()) {
            throw new BizException(403, "只有管理员可以执行这个操作");
        }
        return staff;
    }

    /** 审计用的名字；未登录时给 system，避免 created_by 为空 */
    public String displayNameOrSystem() {
        StaffView staff = getOrNull();
        return staff == null ? "system" : staff.getDisplayName();
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes s ? s.getRequest() : null;
    }
}