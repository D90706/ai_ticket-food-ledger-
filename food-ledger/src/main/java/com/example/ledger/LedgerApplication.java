package com.example.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 食材采购台账系统。
 *
 * 两件事同时成立，而且不冲突：
 *   1 它是一个真的台账系统：能录、能查、能筛选、能统计、能导出
 *   2 它带着我们对「录入」的想法：AI 从一句话里抽成台账草稿，
 *     按法定项目判定合规完整度，缺什么就说什么，
 *     不合规的不许入账，管理员可以打回补录
 *
 * 手填必须完全可用——AI 是加速器，不是唯一入口。
 * 一个录不了账的台账系统，想法再漂亮也没用。
 */
@SpringBootApplication
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
