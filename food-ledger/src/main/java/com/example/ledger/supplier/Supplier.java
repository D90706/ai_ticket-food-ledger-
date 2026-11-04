package com.example.ledger.supplier;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 供应商。
 *
 * 台账合规的一半在这一张表上：证照过期，这笔采购就不合规。
 * 所以证照有效期是必填项级的关注对象，不是备注。
 */
@Data
@TableName("supplier")
public class Supplier {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 供货者名称（台账里也会冗余一份，历史记录不随主数据改名而变） */
    private String name;

    private String address;

    private String contact;

    private String phone;

    /** 食品经营许可证号 */
    private String licenseNo;

    /** 许可证有效期至 */
    private LocalDate licenseExpiry;

    /** 营业执照号 */
    private String businessLicenseNo;

    private LocalDate businessExpiry;

    /** ACTIVE / DISABLED */
    private String status;

    private String remark;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}