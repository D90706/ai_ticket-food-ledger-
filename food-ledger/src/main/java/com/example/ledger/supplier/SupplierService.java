package com.example.ledger.supplier;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ledger.common.BizException;
import com.example.ledger.supplier.dto.SupplierUpsertRequest;
import com.example.ledger.supplier.dto.SupplierView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 供应商服务。
 *
 * 证照预警的计算放在这里，不放前端：
 * 一旦前端算，规则就会在各个页面漂移。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierMapper supplierMapper;

    /** 证照到期预警天数 */
    @Value("${app.ledger.license-warn-days:30}")
    private int warnDays = 30;

    public List<SupplierView> list(String keyword, String status) {
        List<Supplier> rows = supplierMapper.selectList(
                Wrappers.<Supplier>lambdaQuery()
                        .eq(status != null && !status.isBlank(), Supplier::getStatus, status)
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(Supplier::getName, keyword)
                                .or().like(Supplier::getContact, keyword)
                                .or().like(Supplier::getPhone, keyword))
                        .orderByAsc(Supplier::getName));
        return rows.stream().map(this::toView).toList();
    }

    public SupplierView get(Long id) {
        return toView(require(id));
    }

    public SupplierView create(SupplierUpsertRequest request) {
        Supplier supplier = new Supplier();
        apply(supplier, request);
        supplier.setStatus("ACTIVE");
        supplier.setCreatedAt(LocalDateTime.now());
        supplier.setUpdatedAt(LocalDateTime.now());
        supplierMapper.insert(supplier);
        log.info("新增供应商 id={} name={}", supplier.getId(), supplier.getName());
        return toView(supplier);
    }

    public SupplierView update(Long id, SupplierUpsertRequest request) {
        Supplier supplier = require(id);
        apply(supplier, request);
        supplier.setUpdatedAt(LocalDateTime.now());
        supplierMapper.updateById(supplier);
        return toView(supplier);
    }

    public void disable(Long id) {
        Supplier supplier = require(id);
        supplier.setStatus("DISABLED");
        supplier.setUpdatedAt(LocalDateTime.now());
        supplierMapper.updateById(supplier);
    }

    /** 台账录入时按名称找主数据：找不到也不报错，手填的供货者名称同样有效 */
    public Supplier findByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return supplierMapper.selectOne(
                Wrappers.<Supplier>lambdaQuery().eq(Supplier::getName, name.trim()).last("LIMIT 1"));
    }

    // ---------------------------------------------------------- 内部

    private Supplier require(Long id) {
        Supplier supplier = supplierMapper.selectById(id);
        if (supplier == null) {
            throw BizException.notFound("供应商 ");
        }
        return supplier;
    }

    private void apply(Supplier supplier, SupplierUpsertRequest request) {
        supplier.setName(request.getName().trim());
        supplier.setAddress(request.getAddress());
        supplier.setContact(request.getContact());
        supplier.setPhone(request.getPhone());
        supplier.setLicenseNo(request.getLicenseNo());
        supplier.setLicenseExpiry(request.getLicenseExpiry());
        supplier.setBusinessLicenseNo(request.getBusinessLicenseNo());
        supplier.setBusinessExpiry(request.getBusinessExpiry());
        supplier.setRemark(request.getRemark());
    }

    private SupplierView toView(Supplier s) {
        SupplierView v = new SupplierView();
        v.setId(s.getId());
        v.setName(s.getName());
        v.setAddress(s.getAddress());
        v.setContact(s.getContact());
        v.setPhone(s.getPhone());
        v.setLicenseNo(s.getLicenseNo());
        v.setLicenseExpiry(s.getLicenseExpiry());
        v.setBusinessLicenseNo(s.getBusinessLicenseNo());
        v.setBusinessExpiry(s.getBusinessExpiry());
        v.setStatus(s.getStatus());
        v.setRemark(s.getRemark());

        v.setLicenseDaysLeft(daysLeft(s.getLicenseExpiry()));
        v.setBusinessDaysLeft(daysLeft(s.getBusinessExpiry()));
        v.setLicenseWarning(
                (v.getLicenseDaysLeft() != null && v.getLicenseDaysLeft() <= warnDays)
                        || (v.getBusinessDaysLeft() != null && v.getBusinessDaysLeft() <= warnDays));
        return v;
    }

    private Long daysLeft(LocalDate expiry) {
        return expiry == null ? null : ChronoUnit.DAYS.between(LocalDate.now(), expiry);
    }
}