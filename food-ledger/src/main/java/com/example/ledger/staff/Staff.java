package com.example.ledger.staff;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 员工账号。密码只存 BCrypt 哈希。
 *
 * 两个角色：
 *   PURCHASER 采购员 —— 录入台账、看自己的记录、补录
 *   MANAGER   管理员 —— 全部台账、入账、打回、作废、供应商与模板
 */
@Data
@TableName("staff")
public class Staff {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String displayName;

    private String passwordHash;

    /** PURCHASER / MANAGER */
    private String role;

    private Boolean enabled;

    private LocalDateTime createdAt;
}