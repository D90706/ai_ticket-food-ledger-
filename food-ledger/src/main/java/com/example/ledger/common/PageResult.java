package com.example.ledger.common;

import lombok.Data;

import java.util.List;

/**
 * 分页结果。
 *
 * 不直接把 MyBatis-Plus 的 Page 丢给前端：
 * 那会把 records/total/size/current/pages/orders/optimizeCountSql…
 * 一堆内部字段全暴露出去，前端还得猜哪个是行、哪个是总数。
 */
@Data
public class PageResult<T> {

    private List<T> rows;
    private long total;
    private long page;
    private long size;

    public PageResult(List<T> rows, long total, long page, long size) {
        this.rows = rows;
        this.total = total;
        this.page = page;
        this.size = size;
    }
}