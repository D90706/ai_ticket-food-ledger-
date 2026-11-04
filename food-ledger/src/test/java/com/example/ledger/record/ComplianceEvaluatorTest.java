package com.example.ledger.record;

import com.example.ledger.template.TemplateField;
import com.example.ledger.template.TemplateSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合规判定的单元测试。
 *
 * 守四件事：
 *   1 缺法定项目就是 RED——这是「不能入账」的依据，绝不能松
 *   2 法定齐、只缺选填才是 YELLOW
 *   3 过期不算级别，只算提示（否则分不清该补资料还是该退货）
 *   4 判定完全由模板驱动：模板里标了必填，自定义字段也能把级别拉成 RED
 */
class ComplianceEvaluatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);

    private TemplateSchema schema() {
        return new TemplateSchema(1L, "食材采购台账", 1, List.of(
                field("ingredient_name", "名称", true, true),
                field("quantity", "数量", true, true),
                field("batch_no", "生产批号", true, true),
                field("supplier_name", "供货者名称", true, true),
                field("certificate", "索证索票凭证", true, true),
                field("unit_price", "单价", false, false),
                field("origin", "产地", false, false)
        ), 3);
    }

    private TemplateField field(String key, String label, boolean required, boolean fixed) {
        TemplateField f = new TemplateField();
        f.setKey(key);
        f.setLabel(label);
        f.setType("text");
        f.setRequired(required);
        f.setFixed(fixed);
        return f;
    }

    private Map<String, Object> complete() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ingredient_name", "冷鲜猪肉");
        v.put("quantity", 20);
        v.put("batch_no", "P20261026-3");
        v.put("supplier_name", "城南肉联厂");
        v.put("certificate", "检疫证明 2026-A-1234");
        v.put("unit_price", 28.5);
        v.put("origin", "本地");
        return v;
    }

    @Test
    @DisplayName("全齐 → GREEN")
    void completeIsGreen() {
        LedgerQuality q = new ComplianceEvaluator(schema()).evaluate(complete(), TODAY);
        assertEquals(QualityLevel.GREEN, q.getLevel());
        assertEquals(1.0, q.getMetrics().get("requiredRate"));
        assertTrue(q.getMissing().isEmpty());
    }

    @Test
    @DisplayName("缺法定项目 → RED，且把缺的项目名说清楚")
    void missingLegalIsRed() {
        Map<String, Object> v = complete();
        v.remove("batch_no");
        v.remove("certificate");

        LedgerQuality q = new ComplianceEvaluator(schema()).evaluate(v, TODAY);

        assertEquals(QualityLevel.RED, q.getLevel());
        assertTrue(q.blocksPosting(), "RED 必须拦住入账");
        assertTrue(q.getMessages().get(0).contains("生产批号"), q.getMessages().get(0));
        assertTrue(q.getMessages().get(0).contains("索证索票凭证"));
    }

    @Test
    @DisplayName("法定齐、只缺选填 → YELLOW，可入账")
    void missingOptionalIsYellow() {
        Map<String, Object> v = complete();
        v.remove("origin");

        LedgerQuality q = new ComplianceEvaluator(schema()).evaluate(v, TODAY);

        assertEquals(QualityLevel.YELLOW, q.getLevel());
        assertTrue(!q.blocksPosting(), "YELLOW 不该拦住入账");
    }

    @Test
    @DisplayName("过期不改级别，只给提示——补资料和退货是两件事")
    void expiryIsWarningNotLevel() {
        Map<String, Object> v = complete();
        v.put("expiry_date", TODAY.minusDays(2));

        LedgerQuality q = new ComplianceEvaluator(schema()).evaluate(v, TODAY);

        assertEquals(QualityLevel.GREEN, q.getLevel(), "过期不该被算成「记录不全」");
        assertTrue(q.getWarnings().stream().anyMatch(w -> w.contains("已过保质期")),
                q.getWarnings().toString());
    }

    @Test
    @DisplayName("临期提示按模板的天数走")
    void expiryWarnDaysFromTemplate() {
        Map<String, Object> v = complete();
        v.put("expiry_date", TODAY.plusDays(2));

        LedgerQuality q = new ComplianceEvaluator(schema()).evaluate(v, TODAY);
        assertTrue(q.getWarnings().stream().anyMatch(w -> w.contains("距到期仅剩 2 天")),
                q.getWarnings().toString());
    }

    @Test
    @DisplayName("模板里把自定义字段标成必填，它也能把级别拉成 RED")
    void customRequiredCanBlock() {
        TemplateSchema withCustomRequired = new TemplateSchema(1L, "台账", 1, List.of(
                field("ingredient_name", "名称", true, true),
                field("origin", "产地", true, false)   // 自定义但必填
        ), 3);

        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ingredient_name", "土豆");

        LedgerQuality q = new ComplianceEvaluator(withCustomRequired).evaluate(v, TODAY);

        assertEquals(QualityLevel.RED, q.getLevel());
        assertTrue(q.getMessages().get(0).contains("产地"), q.getMessages().get(0));
    }

    @Test
    @DisplayName("空白字符串等于没填：不能被算成已填")
    void blankCountsAsMissing() {
        Map<String, Object> v = complete();
        v.put("batch_no", "   ");

        assertEquals(QualityLevel.RED,
                new ComplianceEvaluator(schema()).evaluate(v, TODAY).getLevel());
    }
}