package com.example.ticket.quality;

import java.util.List;

/**
 * 缺失清单。
 *
 * 计算方式只有一条依据（idea.txt 7.2）：
 *   缺失 = schema 的全部字段 减去 values 中的非空字段
 * 然后把缺失按 required 分流。
 *
 * 刻意不接受「模型自述的缺失清单」——实测模型会漏项。
 * 而集合运算不会漏。
 */
public record MissingReport(
        List<String> required,
        List<String> optional,
        List<String> all) {

    public boolean hasRequiredMissing() {
        return !required.isEmpty();
    }

    public boolean isEmpty() {
        return all.isEmpty();
    }
}
