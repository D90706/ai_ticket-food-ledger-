package com.example.ledger.stats.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 台账统计。
 *
 * 为什么金额要分「总金额」和「已入账金额」：
 * 待入账和退回补录的记录还没被审核过，把它们算进"本月买了多少钱"
 * 会让月度报表虚高。两个数都摆出来，谁看谁都清楚。
 */
@Data
public class StatsOverview {

    private int totalRecords;
    private BigDecimal totalAmount = BigDecimal.ZERO;
    /** 已入账部分 */
    private BigDecimal postedAmount = BigDecimal.ZERO;
    private int postedCount;
    private int pendingCount;
    private int returnedCount;
    private int voidCount;

    /** 合规分布：这是台账质量的直接体现 */
    private int redCount;
    private int yellowCount;
    private int greenCount;

    /** 30 天内到期或已过期 */
    private int expiringCount;

    private List<MonthStat> byMonth = new ArrayList<>();
    private List<NameStat> bySupplier = new ArrayList<>();
    private List<NameStat> byCategory = new ArrayList<>();
    private List<ExpiringItem> expiring = new ArrayList<>();

    @Data
    public static class MonthStat {
        private String month;
        private int count;
        private BigDecimal amount = BigDecimal.ZERO;
    }

    @Data
    public static class NameStat {
        private String name;
        private int count;
        private BigDecimal amount = BigDecimal.ZERO;
    }

    @Data
    public static class ExpiringItem {
        private Long id;
        private String ingredientName;
        private String batchNo;
        private LocalDate expiryDate;
        /** 负数表示已过期 */
        private long daysLeft;
    }
}