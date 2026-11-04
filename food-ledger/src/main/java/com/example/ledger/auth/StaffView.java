package com.example.ledger.auth;

import com.example.ledger.staff.Staff;
import lombok.Data;

import java.io.Serializable;

/** 放进 session 的登录者。不带 passwordHash——会话里不该有凭据。 */
@Data
public class StaffView implements Serializable {

    private Long id;
    private String username;
    private String displayName;
    /** PURCHASER / MANAGER */
    private String role;

    public boolean isManager() {
        return "MANAGER".equals(role);
    }

    public static StaffView of(Staff staff) {
        StaffView v = new StaffView();
        v.setId(staff.getId());
        v.setUsername(staff.getUsername());
        v.setDisplayName(staff.getDisplayName());
        v.setRole(staff.getRole());
        return v;
    }
}