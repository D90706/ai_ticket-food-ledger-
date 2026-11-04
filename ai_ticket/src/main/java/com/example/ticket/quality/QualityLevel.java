package com.example.ticket.quality;

/**
 * 工单级别。
 *
 * 注意这不是「置信度」——它只承诺「信息够不够做决策」，
 * 不承诺「判断对不对」。命名上刻意避开 confidence，
 * 因为一个词带来的过度信任比没有这个指标更危险。
 *
 * 颜色绑定的是「人工能做什么」，不是「分数高低」：
 *   RED    不可直接决策，必须先补信息
 *   YELLOW 可决策，但需先核对依据
 *   GREEN  可直接决策
 */
public enum QualityLevel {

    RED,
    YELLOW,
    GREEN;

    public boolean canDecideDirectly() {
        return this == GREEN;
    }

    /** 黄级要求人工先勾选「已核对依据」 */
    public boolean requiresBasisReview() {
        return this == YELLOW;
    }
}
