package com.example.ledger.supplier.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 供应商列表项。
 *
 * 把「证照还剩几天」算好再给前端：这类判断不该让每个页面各算一遍，
 * 也不该让前端去猜今天是不是过期了。
 */
@Data
public class SupplierView {

    private Long id;
    private String name;
    private String address;
    private String contact;
    private String phone;
    private String licenseNo;
    private LocalDate licenseExpiry;
    private String businessLicenseNo;
    private LocalDate businessExpiry;
    private String status;
    private String remark;

    /** 许可证距到期天数；已过期为负数；无到期日为 null */
    private Long licenseDaysLeft;

    /** 营业执照距到期天数 */
    private Long businessDaysLeft;

    /** 任一证照在预警范围内或已过期 */
    private boolean licenseWarning;
}