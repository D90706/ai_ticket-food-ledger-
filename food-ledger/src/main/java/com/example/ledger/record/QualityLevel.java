package com.example.ledger.record;

/**
 * 合规完整度。
 *
 * 这不是"数据质量分"，而是"这笔采购能不能入账、检查时站不站得住"：
 *
 *   RED    缺法定项目 —— 不能入账（法律要求记录的项目没记全）
 *   YELLOW 法定齐了，但缺凭证或选填项 —— 可入账，检查前补齐
 *   GREEN  全齐
 *
 * 颜色必须同时配文字，因为报表要黑白打印——
 * 只靠颜色，打印出来分级就消失了。
 */
public enum QualityLevel {

    RED("不合规"),
    YELLOW("待补证"),
    GREEN("记录完整");

    private final String label;

    QualityLevel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}