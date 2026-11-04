package com.example.ledger.record;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把一条记录摊平成「字段 key → 值」。
 *
 * 为什么需要它：判定、导出、对比补录前后差异，都要按模板的 key 取值，
 * 而不是按列名。法定项目的 key 与列一一对应，自定义字段在 extra_json 里。
 */
public final class LedgerValues {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LedgerValues() {
    }

    public static Map<String, Object> of(LedgerRecord r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ingredient_name", r.getIngredientName());
        v.put("spec", r.getSpec());
        v.put("quantity", r.getQuantity());
        v.put("unit", r.getUnit());
        v.put("production_date", r.getProductionDate());
        v.put("batch_no", r.getBatchNo());
        v.put("shelf_life", r.getShelfLife());
        v.put("purchase_date", r.getPurchaseDate());
        v.put("supplier_name", r.getSupplierName());
        v.put("supplier_address", r.getSupplierAddress());
        v.put("supplier_phone", r.getSupplierPhone());
        v.put("certificate", r.getCertificate());
        v.put("certificate_ok", r.getCertificateOk());
        v.put("expiry_date", r.getExpiryDate());
        v.put("unit_price", r.getUnitPrice());
        v.put("amount", r.getAmount());
        v.put("warehouse", r.getWarehouse());
        v.put("receiver", r.getReceiver());
        // 附件也进值表：合规判定要靠它判断「索证索票凭证」是否已经用照片提供了。
        // 不放进来，就会出现「明明传了凭证照片，仍提示缺凭证」。
        v.put("attachments", attachmentsOf(r));
        v.putAll(extraOf(r));
        return v;
    }

    public static Map<String, Object> extraOf(LedgerRecord r) {
        if (r.getExtraJson() == null || r.getExtraJson().isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(r.getExtraJson(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 附件列（JSON 数组字符串）→ List，解析失败按空处理，不让脏数据把判定搞崩 */
    private static java.util.List<java.util.Map<String, Object>> attachmentsOf(LedgerRecord r) {
        if (r.getAttachments() == null || r.getAttachments().isBlank()) {
            return java.util.List.of();
        }
        try {
            return MAPPER.readValue(r.getAttachments(),
                    new TypeReference<java.util.List<java.util.Map<String, Object>>>() {
                    });
        } catch (Exception e) {
            return java.util.List.of();
        }
    }
}