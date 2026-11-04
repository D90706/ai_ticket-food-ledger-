package com.example.ledger.stats;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ledger.category.Category;
import com.example.ledger.category.CategoryMapper;
import com.example.ledger.record.LedgerRecord;
import com.example.ledger.record.LedgerRecordMapper;
import com.example.ledger.record.LedgerStatus;
import com.example.ledger.record.QualityLevel;
import com.example.ledger.stats.dto.StatsOverview;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计。
 *
 * 聚合放在 Java 里做，不用 SQL 的 GROUP BY：
 * 台账的量级（一个食堂一年几千行）远没到需要数据库聚合的程度，
 * 而 Java 里算能把"已作废不计入金额""待入账单独算"这类规则写在一处，
 * 比在三条 SQL 里各解释一遍可靠。
 *
 * 等到真有几百万行再换 SQL，那时候这段代码是唯一要改的地方。
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private static final int EXPIRING_DAYS = 30;
    private static final int TOP_N = 8;

    private final LedgerRecordMapper recordMapper;
    private final CategoryMapper categoryMapper;

    public StatsOverview overview(LocalDate from, LocalDate to) {
        List<LedgerRecord> rows = recordMapper.selectList(Wrappers.<LedgerRecord>lambdaQuery()
                .ge(from != null, LedgerRecord::getPurchaseDate, from)
                .le(to != null, LedgerRecord::getPurchaseDate, to)
                .orderByAsc(LedgerRecord::getPurchaseDate));

        StatsOverview stats = new StatsOverview();
        stats.setTotalRecords(rows.size());

        Map<String, StatsOverview.MonthStat> months = new LinkedHashMap<>();
        Map<String, StatsOverview.NameStat> suppliers = new HashMap<>();
        Map<String, StatsOverview.NameStat> categories = new HashMap<>();
        Map<Long, String> categoryNames = categoryNames();

        LocalDate today = LocalDate.now();

        for (LedgerRecord r : rows) {
            boolean isVoid = LedgerStatus.VOID.name().equals(r.getStatus());
            boolean isPosted = LedgerStatus.POSTED.name().equals(r.getStatus());

            BigDecimal amount = r.getAmount() == null ? BigDecimal.ZERO : r.getAmount();

            // 已作废的记录不计入任何金额，但计入"作废条数"
            if (!isVoid) {
                stats.setTotalAmount(stats.getTotalAmount().add(amount));
            }
            if (isPosted) {
                stats.setPostedAmount(stats.getPostedAmount().add(amount));
                stats.setPostedCount(stats.getPostedCount() + 1);
            }
            if (LedgerStatus.PENDING.name().equals(r.getStatus())) {
                stats.setPendingCount(stats.getPendingCount() + 1);
            }
            if (LedgerStatus.RETURNED.name().equals(r.getStatus())) {
                stats.setReturnedCount(stats.getReturnedCount() + 1);
            }
            if (isVoid) {
                stats.setVoidCount(stats.getVoidCount() + 1);
            }

            if (QualityLevel.RED.name().equals(r.getQualityLevel())) {
                stats.setRedCount(stats.getRedCount() + 1);
            } else if (QualityLevel.YELLOW.name().equals(r.getQualityLevel())) {
                stats.setYellowCount(stats.getYellowCount() + 1);
            } else if (QualityLevel.GREEN.name().equals(r.getQualityLevel())) {
                stats.setGreenCount(stats.getGreenCount() + 1);
            }

            // 月度
            if (r.getPurchaseDate() != null && !isVoid) {
                String month = YearMonth.from(r.getPurchaseDate()).toString();
                StatsOverview.MonthStat ms = months.computeIfAbsent(month, k -> {
                    StatsOverview.MonthStat m = new StatsOverview.MonthStat();
                    m.setMonth(k);
                    return m;
                });
                ms.setCount(ms.getCount() + 1);
                ms.setAmount(ms.getAmount().add(amount));
            }

            // 供应商
            if (r.getSupplierName() != null && !r.getSupplierName().isBlank() && !isVoid) {
                StatsOverview.NameStat ns = suppliers.computeIfAbsent(r.getSupplierName(), k -> {
                    StatsOverview.NameStat s = new StatsOverview.NameStat();
                    s.setName(k);
                    return s;
                });
                ns.setCount(ns.getCount() + 1);
                ns.setAmount(ns.getAmount().add(amount));
            }

            // 品类
            if (r.getCategoryId() != null && !isVoid) {
                String name = categoryNames.getOrDefault(r.getCategoryId(), "未分类");
                StatsOverview.NameStat ns = categories.computeIfAbsent(name, k -> {
                    StatsOverview.NameStat s = new StatsOverview.NameStat();
                    s.setName(k);
                    return s;
                });
                ns.setCount(ns.getCount() + 1);
                ns.setAmount(ns.getAmount().add(amount));
            }

            // 临期 / 过期：已作废的不算，过期天数按今天算
            if (r.getExpiryDate() != null && !isVoid) {
                long days = ChronoUnit.DAYS.between(today, r.getExpiryDate());
                if (days <= EXPIRING_DAYS) {
                    stats.setExpiringCount(stats.getExpiringCount() + 1);
                    StatsOverview.ExpiringItem item = new StatsOverview.ExpiringItem();
                    item.setId(r.getId());
                    item.setIngredientName(r.getIngredientName());
                    item.setBatchNo(r.getBatchNo());
                    item.setExpiryDate(r.getExpiryDate());
                    item.setDaysLeft(days);
                    stats.getExpiring().add(item);
                }
            }
        }

        stats.getExpiring().sort(Comparator.comparingLong(StatsOverview.ExpiringItem::getDaysLeft));

        stats.setByMonth(new ArrayList<>(months.values()));
        stats.setBySupplier(topN(suppliers.values()));
        stats.setByCategory(topN(categories.values()));
        return stats;
    }

    private List<StatsOverview.NameStat> topN(java.util.Collection<StatsOverview.NameStat> values) {
        return values.stream()
                .sorted(Comparator.comparing(StatsOverview.NameStat::getAmount).reversed())
                .limit(TOP_N)
                .toList();
    }

    private Map<Long, String> categoryNames() {
        Map<Long, String> map = new HashMap<>();
        for (Category c : categoryMapper.selectList(null)) {
            map.put(c.getId(), c.getName());
        }
        return map;
    }
}