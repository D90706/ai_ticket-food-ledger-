package com.example.ledger.record.dto;

import lombok.Data;

import java.time.LocalDate;

/** 台账查询条件。全部可空，空即不过滤。 */
@Data
public class LedgerQuery {

    private LocalDate dateFrom;
    private LocalDate dateTo;
    private String supplierName;
    private Long supplierId;
    private Long categoryId;
    private String status;
    private String level;
    private String keyword;
    private String batchNo;
    private String createdBy;

    /**
     * MINE = 采购员的「我的记录」：我录的 + 需要我补录的（被退回的）。
     * 只看 createdBy 会把「管理员录的、被退回给采购员补」的记录漏掉，
     * 结果是那条记录谁都补不了。
     */
    private String scope;

    /** true = 只看临期（含已过期） */
    private Boolean expiring;

    /** 分页：page 从 1 开始；size 上限在服务端夹住，防止被要求一次取十万条 */
    private Integer page;
    private Integer size;

    public int pageNo() {
        return page == null || page < 1 ? 1 : page;
    }

    public int pageSize() {
        if (size == null || size < 1) {
            return 20;
        }
        return Math.min(size, 200);
    }
}