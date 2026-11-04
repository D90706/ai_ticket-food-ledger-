package com.example.ledger.supplier.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;

@Data
public class SupplierUpsertRequest {

    @NotBlank(message = "供应商名称不能为空")
    private String name;

    private String address;
    private String contact;
    private String phone;
    private String licenseNo;
    private LocalDate licenseExpiry;
    private String businessLicenseNo;
    private LocalDate businessExpiry;
    private String remark;
}