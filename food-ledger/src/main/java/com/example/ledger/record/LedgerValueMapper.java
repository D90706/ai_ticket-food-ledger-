package com.example.ledger.record;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ledger.category.Category;
import com.example.ledger.category.CategoryMapper;
import com.example.ledger.record.dto.LedgerUpsertRequest;
import com.example.ledger.template.TemplateSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 字段值 → 录入请求的映射。
 *
 * 为什么单独抽出来：
 * AI 录入是「模型给一张 key→value 的表」，手填是「表单给一张 key→value 的表」，
 * 两者本质是同一个东西。如果各写一份映射，迟早会出现
 * 「AI 填进来的 shelf_life 能存，手填的丢了」这种分叉。
 *
 * 规则很简单：法定项目对应固定列，其余进 extra_json。
 */
@Component
@RequiredArgsConstructor
public class LedgerValueMapper {

    private final CategoryMapper categoryMapper;

    public LedgerUpsertRequest toRequest(TemplateSchema schema, Map<String, Object> values) {
        LedgerUpsertRequest request = new LedgerUpsertRequest();
        Map<String, Object> extra = new LinkedHashMap<>();

        // 按「值的 key」分发，而不是只遍历模板字段。
        // 只遍历模板字段的话，像 attachments 这种「有列但不在模板里」的键
        // 会被当成自定义字段塞进 extra_json，对应的列永远空着——
        // 这个 bug 就是这么来的（附件能上传、能访问，但挂不到台账上）。
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value);
            if (text.isBlank()) {
                continue;
            }
            switch (key) {
                case "ingredient_name" -> request.setIngredientName(text.trim());
                case "spec" -> request.setSpec(text.trim());
                case "quantity" -> request.setQuantity(decimal(text));
                case "unit" -> request.setUnit(text.trim());
                case "production_date" -> request.setProductionDate(date(text));
                case "batch_no" -> request.setBatchNo(text.trim());
                case "shelf_life" -> request.setShelfLife(text.trim());
                case "purchase_date" -> request.setPurchaseDate(date(text));
                case "supplier_name" -> request.setSupplierName(text.trim());
                case "supplier_address" -> request.setSupplierAddress(text.trim());
                case "supplier_phone" -> request.setSupplierPhone(text.trim());
                case "certificate" -> request.setCertificate(text.trim());
                case "expiry_date" -> request.setExpiryDate(date(text));
                case "unit_price" -> request.setUnitPrice(decimal(text));
                case "amount" -> request.setAmount(decimal(text));
                case "warehouse" -> request.setWarehouse(text.trim());
                case "receiver" -> request.setReceiver(text.trim());
                case "attachments" -> request.setAttachments(attachmentsOf(value));
                case "certificate_ok" -> request.setCertificateOk(Boolean.valueOf(text));
                // 品类在界面上是「名称」，库里存 category_id：
                // 让模型和人都只面对中文名，id 在服务端解析，前端不需要知道品类表
                case "category" -> request.setCategoryId(categoryIdOf(text));
                default -> extra.put(key, value);
            }
        }
        request.setExtra(extra);
        return request;
    }

    /**
     * 容忍「20斤」「28.5 元」这类带杂质的写法。
     * 模型会这么写，人也懒得删单位——两种输入都得吃得下。
     */
    /**
     * 附件在 JSON 里是对象数组，转成 List<Attachment>。
     * 走 Jackson 而不是手写字段映射：以后给 Attachment 加字段不用改这里。
     */
    @SuppressWarnings("unchecked")
    private java.util.List<com.example.ledger.common.Attachment> attachmentsOf(Object value) {
        if (value == null) {
            return null;
        }
        return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
                value, new com.fasterxml.jackson.core.type.TypeReference<
                        java.util.List<com.example.ledger.common.Attachment>>() {
                });
    }

    /**
     * 品类名 → category_id。
     *
     * 找不到就返回 null（而不是报错）：品类是选填项，
     * 一个没维护进主数据的名字不该让整条台账录不进去。
     * 但要在日志里留痕，否则"品类丢了"会像这次一样查很久。
     */
    private Long categoryIdOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Category category = categoryMapper.selectOne(Wrappers.<Category>lambdaQuery()
                .eq(Category::getName, name.trim()).last("LIMIT 1"));
        if (category == null) {
            org.slf4j.LoggerFactory.getLogger(LedgerValueMapper.class)
                    .warn("品类「{}」不在主数据里，本次按未分类保存", name);
            return null;
        }
        return category.getId();
    }

    private BigDecimal decimal(String text) {
        try {
            String cleaned = text.replaceAll("[^0-9.\\-]", "");
            return cleaned.isBlank() ? null : new BigDecimal(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate date(String text) {
        try {
            String cleaned = text.replace('/', '-').replace('.', '-').trim();
            if (cleaned.length() > 10) {
                cleaned = cleaned.substring(0, 10);
            }
            return LocalDate.parse(cleaned);
        } catch (Exception e) {
            return null;
        }
    }
}
