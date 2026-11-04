package com.example.ledger.record;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ledger.category.Category;
import com.example.ledger.category.CategoryMapper;
import com.example.ledger.common.BizException;
import com.example.ledger.common.PageResult;
import com.example.ledger.record.dto.LedgerDetail;
import com.example.ledger.record.dto.LedgerQuery;
import com.example.ledger.record.dto.LedgerUpsertRequest;
import com.example.ledger.record.dto.LedgerView;
import com.example.ledger.template.TemplateField;
import com.example.ledger.template.TemplateSchema;
import com.example.ledger.template.TemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 台账核心服务。
 *
 * 三条不可让步的规则：
 *   1 不合规（RED）的记录不能入账 —— 法律要求记的项目没记全，就不算台账
 *   2 台账不删除，只能作废 —— 删行等于抹掉痕迹，作废要比删除诚实
 *   3 修改必须带 version —— 两个人同时改一行时，宁可报冲突，不要静默覆盖
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    public static final int MAX_RETURNS = 2;

    private final LedgerRecordMapper recordMapper;
    private final TemplateService templateService;
    private final CategoryMapper categoryMapper;
    private final ObjectMapper objectMapper;

    private static final Map<String, String> STATUS_LABEL = Map.of(
            "PENDING", "待入账",
            "POSTED", "已入账",
            "RETURNED", "退回补录",
            "VOID", "已作废");

    // ============================================================ 查询

    /** 全量列表：导出用。分页不要走这条，否则导出会被页大小截断 */
    public List<LedgerView> list(LedgerQuery q) {
        Map<Long, String> categoryNames = categoryNames();
        return recordMapper.selectList(wrapperOf(q)).stream()
                .map(r -> toView(r, categoryNames))
                .toList();
    }

    /** 分页列表：界面用 */
    public PageResult<LedgerView> page(LedgerQuery q) {
        Map<Long, String> categoryNames = categoryNames();
        Page<LedgerRecord> request = new Page<>(q.pageNo(), q.pageSize());
        Page<LedgerRecord> result = recordMapper.selectPage(request, wrapperOf(q));
        List<LedgerView> rows = result.getRecords().stream()
                .map(r -> toView(r, categoryNames))
                .toList();
        return new PageResult<>(rows, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private LambdaQueryWrapper<LedgerRecord> wrapperOf(LedgerQuery q) {
        LambdaQueryWrapper<LedgerRecord> wrapper = Wrappers.<LedgerRecord>lambdaQuery()
                .ge(q.getDateFrom() != null, LedgerRecord::getPurchaseDate, q.getDateFrom())
                .le(q.getDateTo() != null, LedgerRecord::getPurchaseDate, q.getDateTo())
                .eq(q.getSupplierId() != null, LedgerRecord::getSupplierId, q.getSupplierId())
                .eq(q.getCategoryId() != null && q.getCategoryId() > 0,
                        LedgerRecord::getCategoryId, q.getCategoryId())
                // categoryId = -1 表示「未分类」：老数据的品类为空，
                // 不给这个入口的话，这些记录在任何品类筛选下都看不见，等于消失
                .isNull(q.getCategoryId() != null && q.getCategoryId() < 0,
                        LedgerRecord::getCategoryId)
                .eq(notBlank(q.getStatus()), LedgerRecord::getStatus, q.getStatus())
                .eq(notBlank(q.getLevel()), LedgerRecord::getQualityLevel, q.getLevel())
                .eq(notBlank(q.getBatchNo()), LedgerRecord::getBatchNo, q.getBatchNo())
                /*
                 * 采购员的「我的记录」有两种记录：
                 *   1 我录的
                 *   2 退回给我补的 —— 不论是谁录的
                 * 只管第 1 种的话，「管理员录了一条、发现有问题退回给采购员」的那条
                 * 会从采购员页面上消失，谁都补不了，流程就断在这里。
                 *
                 * 明确筛了某个状态时按原语义走（否则筛「已入账」会混进退回的）。
                 */
                .and(notBlank(q.getCreatedBy()), w -> {
                    if ("MINE".equalsIgnoreCase(q.getScope()) && !notBlank(q.getStatus())) {
                        w.eq(LedgerRecord::getCreatedBy, q.getCreatedBy())
                                .or().eq(LedgerRecord::getStatus, LedgerStatus.RETURNED.name());
                    } else {
                        w.eq(LedgerRecord::getCreatedBy, q.getCreatedBy());
                    }
                })
                .and(notBlank(q.getSupplierName()), w -> w
                        .like(LedgerRecord::getSupplierName, q.getSupplierName()))
                .and(notBlank(q.getKeyword()), w -> w
                        .like(LedgerRecord::getIngredientName, q.getKeyword())
                        .or().like(LedgerRecord::getSupplierName, q.getKeyword())
                        .or().like(LedgerRecord::getBatchNo, q.getKeyword()))
                .orderByDesc(LedgerRecord::getPurchaseDate)
                .orderByDesc(LedgerRecord::getId);

        if (Boolean.TRUE.equals(q.getExpiring())) {
            LocalDate limit = LocalDate.now().plusDays(30);
            wrapper.isNotNull(LedgerRecord::getExpiryDate)
                    .le(LedgerRecord::getExpiryDate, limit);
        }

        return wrapper;
    }

    public LedgerDetail get(Long id) {
        return toDetail(require(id));
    }

    /** 同一批号还有哪些记录：出了问题的食材，按批号能反查到底 */
    public List<LedgerView> traceByBatch(String batchNo) {
        if (batchNo == null || batchNo.isBlank()) {
            return List.of();
        }
        Map<Long, String> categoryNames = categoryNames();
        return recordMapper.selectList(Wrappers.<LedgerRecord>lambdaQuery()
                        .eq(LedgerRecord::getBatchNo, batchNo)
                        .orderByDesc(LedgerRecord::getPurchaseDate))
                .stream().map(r -> toView(r, categoryNames)).toList();
    }

    // ============================================================ 录入

    /** 手填录入 */
    @Transactional
    public LedgerDetail create(LedgerUpsertRequest request, String actor) {
        return create(request, actor, "MANUAL", null, null, null);
    }

    /**
     * 录入（手填与 AI 共用）。
     *
     * 两条入口走同一段代码，不是图省事：
     * 如果 AI 走一条单独的写入路径，它的判定规则迟早会和手填分叉，
     * 到时候同一批食材因为录入方式不同被判成两个级别。
     */
    @Transactional
    public LedgerDetail create(LedgerUpsertRequest request, String actor, String sourceType,
                               Long sessionId, String summary, Map<String, String> basis) {
        if (request.getIngredientName() == null || request.getIngredientName().isBlank()) {
            throw new BizException("名称不能为空——台账没有名目就不成记录");
        }

        TemplateSchema schema = templateService.defaultSchema();
        LedgerRecord record = new LedgerRecord();
        apply(record, request);

        record.setTemplateId(schema.templateId());
        record.setTemplateVersion(schema.version());
        record.setSchemaSnapshot(write(schema));
        record.setSourceType(sourceType == null ? "MANUAL" : sourceType);
        record.setSourceSessionId(sessionId);
        record.setSummary(summary);
        if (basis != null && !basis.isEmpty()) {
            record.setBasis(write(basis));
        }
        record.setStatus(LedgerStatus.PENDING.name());
        record.setRevision(1);
        record.setReturnCount(0);
        // 显式置 0：MyBatis-Plus 插入时会跳过 null 字段（DB 用默认值 0），
        // 但内存里的实体仍是 null，于是紧接着返回给前端的 version 就是 null，
        // 前端拿着 null 去入账只会得到「缺少 version」。这一步不能省。
        record.setVersion(0);
        record.setCreatedBy(actor);
        record.setLastActor(actor);
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());

        evaluate(record, schema);

        recordMapper.insert(record);
        log.info("台账录入 id={} 食材={} 级别={}", record.getId(),
                record.getIngredientName(), record.getQualityLevel());
        return toDetail(record);
    }

    @Transactional
    public LedgerDetail update(Long id, LedgerUpsertRequest request, String actor) {
        LedgerRecord record = require(id);
        if (!LedgerStatus.valueOf(record.getStatus()).isEditable()) {
            throw new BizException(409, "只有「待入账」或「退回补录」的记录可以修改，当前是"
                    + STATUS_LABEL.get(record.getStatus()));
        }
        requireVersion(record, request.getVersion());
        // 修改用合并语义：请求里为 null 的字段保留原值。
        // 台账里「改一个备注顺手清空十个字段」是不能接受的失误。
        apply(record, request, true);
        // 补录后修订号 +1：能看出这行内容改过几次
        record.setRevision((record.getRevision() == null ? 1 : record.getRevision()) + 1);
        record.setLastActor(actor);
        record.setUpdatedAt(LocalDateTime.now());

        /*
         * 补录完要把状态从「退回补录」挪回「待入账」。
         *
         * 不改的话，记录会永远停在「退回补录」：
         * 采购员补完了，管理员那边看不出「这条已经补好了、可以再审核」，
         * 只能靠人工去翻。工单流里这叫「退回后没有回流」，是流程断点。
         *
         * 补完仍然不合规也照样回到待入账——级别会显示「不合规」，
         * 管理员看到就知道还不能入账，可以再退回一次（有次数上限兜着）。
         */
        boolean fromReturned = LedgerStatus.RETURNED.name().equals(record.getStatus());
        if (fromReturned) {
            record.setStatus(LedgerStatus.PENDING.name());
        }

        TemplateSchema schema = schemaOf(record);
        evaluate(record, schema);

        record.setVersion(request.getVersion());
        int rows = recordMapper.updateById(record);
        if (rows == 0) {
            throw new BizException(409, "这行台账刚被别人改过，请刷新后重试");
        }
        return toDetail(record);
    }

    // ============================================================ 状态流转

    @Transactional
    public LedgerDetail post(Long id, Integer version, String actor) {
        LedgerRecord record = require(id);
        requireVersion(record, version);
        LedgerStatus current = LedgerStatus.valueOf(record.getStatus());
        if (!current.canTransitionTo(LedgerStatus.POSTED)) {
            throw new BizException(409, "当前状态（" + STATUS_LABEL.get(record.getStatus())
                    + "）不能入账");
        }
        if (QualityLevel.RED.name().equals(record.getQualityLevel())) {
            throw new BizException(409, "这条记录缺法定项目，不能入账——请先补齐或退回补录");
        }

        record.setStatus(LedgerStatus.POSTED.name());
        record.setPostedBy(actor);
        record.setPostedAt(LocalDateTime.now());
        record.setLastActor(actor);
        record.setUpdatedAt(LocalDateTime.now());
        record.setVersion(version);
        save(record);
        log.info("台账入账 id={} by={}", id, actor);
        return toDetail(record);
    }

    @Transactional
    public LedgerDetail returnBack(Long id, Integer version, String note, String actor) {
        LedgerRecord record = require(id);
        requireVersion(record, version);
        LedgerStatus current = LedgerStatus.valueOf(record.getStatus());
        if (!current.canTransitionTo(LedgerStatus.RETURNED)) {
            throw new BizException(409, "当前状态（" + STATUS_LABEL.get(record.getStatus())
                    + "）不能退回补录");
        }
        int used = record.getReturnCount() == null ? 0 : record.getReturnCount();
        if (used >= MAX_RETURNS) {
            throw new BizException("这条记录已退回 " + used + " 次，请直接补录或作废");
        }

        record.setStatus(LedgerStatus.RETURNED.name());
        record.setReturnCount(used + 1);
        if (note != null && !note.isBlank()) {
            record.setReturnNote(note.trim());
        }
        record.setLastActor(actor);
        record.setUpdatedAt(LocalDateTime.now());
        record.setVersion(version);
        save(record);
        return toDetail(record);
    }

    @Transactional
    public LedgerDetail voidRecord(Long id, Integer version, String reason, String actor) {
        LedgerRecord record = require(id);
        requireVersion(record, version);
        if (LedgerStatus.VOID.name().equals(record.getStatus())) {
            throw new BizException(409, "这条记录已经作废了");
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException("作废必须填原因——台账不允许无声消失");
        }

        record.setStatus(LedgerStatus.VOID.name());
        record.setVoidReason(reason.trim());
        record.setLastActor(actor);
        record.setUpdatedAt(LocalDateTime.now());
        record.setVersion(version);
        save(record);
        log.info("台账作废 id={} reason={} by={}", id, reason, actor);
        return toDetail(record);
    }

    // ============================================================ 判定

    /** 手填与 AI 录入共用同一套判定，否则两种入口会给出两套结论 */
    private void evaluate(LedgerRecord record, TemplateSchema schema) {
        Map<String, Object> values = LedgerValues.of(record);
        // 品类存的是 category_id，但判定与展示都按模板里的 key=「category」取值。
        // 不在这里补上去，品类永远是「缺失」——这是同一类缺陷的第四次：
        // 值其实有，只是不在这张「值表」里。以后新增字段要同步检查这里。
        if (record.getCategoryId() != null) {
            Category category = categoryMapper.selectById(record.getCategoryId());
            if (category != null) {
                values.put("category", category.getName());
            }
        }
        LedgerQuality quality = new ComplianceEvaluator(schema)
                .evaluate(values, LocalDate.now());
        record.setQualityLevel(quality.getLevel().name());
        record.setQualityDetail(write(quality));
    }

    public TemplateSchema schemaOf(LedgerRecord record) {
        // 优先用记录自己的快照：模板后来改过，历史记录仍按当时那版判定
        if (record.getSchemaSnapshot() != null && !record.getSchemaSnapshot().isBlank()) {
            try {
                return objectMapper.readValue(record.getSchemaSnapshot(), TemplateSchema.class);
            } catch (Exception ignored) {
                // 快照损坏才回查模板
            }
        }
        return templateService.getSchema(record.getTemplateId());
    }

    // ============================================================ 内部

    private LedgerRecord require(Long id) {
        LedgerRecord record = recordMapper.selectById(id);
        if (record == null) {
            throw BizException.notFound("台账记录 ");
        }
        return record;
    }

    private void requireVersion(LedgerRecord record, Integer version) {
        if (version == null) {
            throw new BizException(400, "缺少 version，拒绝无锁更新");
        }
        if (!version.equals(record.getVersion())) {
            throw new BizException(409, "这条台账刚被别人改过（你看到的是 v" + version
                    + "，当前是 v" + record.getVersion() + "），请刷新后重试");
        }
    }

    private void save(LedgerRecord record) {
        int rows = recordMapper.updateById(record);
        if (rows == 0) {
            throw new BizException(409, "这行台账刚被别人改过，请刷新后重试");
        }
    }

    /** 全量覆盖：新建时用 */
    private void apply(LedgerRecord record, LedgerUpsertRequest r) {
        apply(record, r, false);
    }

    /**
     * @param merge true = 请求里为 null 的字段保留原值（修改时用）
     */
    private void apply(LedgerRecord record, LedgerUpsertRequest r, boolean merge) {
        if (!merge || r.getIngredientName() != null) { record.setIngredientName(trim(r.getIngredientName())); }
        if (!merge || r.getSpec() != null) { record.setSpec(r.getSpec()); }
        if (!merge || r.getQuantity() != null) { record.setQuantity(r.getQuantity()); }
        if (!merge || r.getUnit() != null) { record.setUnit(r.getUnit()); }
        if (!merge || r.getProductionDate() != null) { record.setProductionDate(r.getProductionDate()); }
        if (!merge || r.getBatchNo() != null) { record.setBatchNo(r.getBatchNo()); }
        if (!merge || r.getShelfLife() != null) { record.setShelfLife(r.getShelfLife()); }
        if (!merge || r.getPurchaseDate() != null) { record.setPurchaseDate(r.getPurchaseDate()); }
        if (!merge || r.getSupplierName() != null) { record.setSupplierName(r.getSupplierName()); }
        if (!merge || r.getSupplierAddress() != null) { record.setSupplierAddress(r.getSupplierAddress()); }
        if (!merge || r.getSupplierPhone() != null) { record.setSupplierPhone(r.getSupplierPhone()); }
        if (!merge || r.getCertificate() != null) { record.setCertificate(r.getCertificate()); }
        record.setAttachments(write(r.getAttachments() == null ? List.of() : r.getAttachments()));
        if (!merge || r.getExpiryDate() != null) { record.setExpiryDate(r.getExpiryDate()); }
        if (!merge || r.getCategoryId() != null) { record.setCategoryId(r.getCategoryId()); }
        if (!merge || r.getSupplierId() != null) { record.setSupplierId(r.getSupplierId()); }
        if (!merge || r.getUnitPrice() != null) { record.setUnitPrice(r.getUnitPrice()); }
        if (!merge || r.getAmount() != null) { record.setAmount(r.getAmount() != null ? r.getAmount() : computeAmount(r)); }
        if (!merge || r.getWarehouse() != null) { record.setWarehouse(r.getWarehouse()); }
        if (!merge || r.getReceiver() != null) { record.setReceiver(r.getReceiver()); }
        if (!merge || r.getRemark() != null) { record.setRemark(r.getRemark()); }
        if (!merge || r.getExtra() != null) { record.setExtraJson(write(r.getExtra() == null ? new LinkedHashMap<>() : r.getExtra())); }

        // 凭证标记：没显式勾选就按"填了凭证号"推断，避免每条都要人点一次
        record.setCertificateOk(r.getCertificateOk() != null
                ? r.getCertificateOk()
                : notBlank(record.getCertificate()));
    }

    private BigDecimal computeAmount(LedgerUpsertRequest r) {
        if (r.getQuantity() == null || r.getUnitPrice() == null) {
            return null;
        }
        return r.getQuantity().multiply(r.getUnitPrice()).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private LedgerView toView(LedgerRecord r, Map<Long, String> categoryNames) {
        LedgerView v = new LedgerView();
        v.setId(r.getId());
        v.setIngredientName(r.getIngredientName());
        v.setSpec(r.getSpec());
        v.setQuantity(r.getQuantity());
        v.setUnit(r.getUnit());
        v.setBatchNo(r.getBatchNo());
        v.setProductionDate(r.getProductionDate());
        v.setShelfLife(r.getShelfLife());
        v.setSupplierAddress(r.getSupplierAddress());
        v.setSupplierPhone(r.getSupplierPhone());
        v.setCertificate(r.getCertificate());
        v.setRevision(r.getRevision());
        v.setPurchaseDate(r.getPurchaseDate());
        v.setExpiryDate(r.getExpiryDate());
        v.setSupplierName(r.getSupplierName());
        v.setCategoryId(r.getCategoryId());
        v.setCategoryName(r.getCategoryId() == null ? null : categoryNames.get(r.getCategoryId()));
        v.setUnitPrice(r.getUnitPrice());
        v.setAmount(r.getAmount());
        v.setWarehouse(r.getWarehouse());
        v.setReceiver(r.getReceiver());
        v.setSummary(r.getSummary());
        v.setQualityLevel(r.getQualityLevel());
        v.setQualityLabel(QualityLevel.valueOf(r.getQualityLevel()).getLabel());
        v.setStatus(r.getStatus());
        v.setStatusLabel(STATUS_LABEL.getOrDefault(r.getStatus(), r.getStatus()));
        v.setReturnCount(r.getReturnCount());
        v.setSourceType(r.getSourceType());
        v.setCreatedBy(r.getCreatedBy());
        v.setCreatedAt(r.getCreatedAt());
        v.setExpiryDaysLeft(r.getExpiryDate() == null ? null
                : ChronoUnit.DAYS.between(LocalDate.now(), r.getExpiryDate()));
        return v;
    }

    private LedgerDetail toDetail(LedgerRecord r) {
        LedgerDetail d = new LedgerDetail();
        d.setId(r.getId());
        d.setIngredientName(r.getIngredientName());
        d.setSpec(r.getSpec());
        d.setQuantity(r.getQuantity());
        d.setUnit(r.getUnit());
        d.setProductionDate(r.getProductionDate());
        d.setBatchNo(r.getBatchNo());
        d.setShelfLife(r.getShelfLife());
        d.setPurchaseDate(r.getPurchaseDate());
        d.setSupplierName(r.getSupplierName());
        d.setSupplierAddress(r.getSupplierAddress());
        d.setSupplierPhone(r.getSupplierPhone());
        d.setCertificate(r.getCertificate());
        d.setCertificateOk(r.getCertificateOk());
        d.setAttachments(readAttachments(r));
        d.setExpiryDate(r.getExpiryDate());
        d.setCategoryId(r.getCategoryId());
        Map<Long, String> names = categoryNames();
        d.setCategoryName(r.getCategoryId() == null ? null : names.get(r.getCategoryId()));
        d.setSupplierId(r.getSupplierId());
        d.setUnitPrice(r.getUnitPrice());
        d.setAmount(r.getAmount());
        d.setWarehouse(r.getWarehouse());
        d.setReceiver(r.getReceiver());
        d.setRemark(r.getRemark());
        d.setExtra(LedgerValues.extraOf(r));
        d.setSummary(r.getSummary());

        d.setQualityLevel(r.getQualityLevel());
        d.setQualityLabel(QualityLevel.valueOf(r.getQualityLevel()).getLabel());
        d.setQuality(readQuality(r));
        d.setWarnings(d.getQuality() == null ? List.of() : d.getQuality().getWarnings());

        d.setStatus(r.getStatus());
        d.setStatusLabel(STATUS_LABEL.getOrDefault(r.getStatus(), r.getStatus()));
        d.setSourceType(r.getSourceType());
        d.setSourceSessionId(r.getSourceSessionId());
        d.setRevision(r.getRevision());
        d.setReturnCount(r.getReturnCount());
        d.setReturnNote(r.getReturnNote());
        d.setVersion(r.getVersion());
        d.setCreatedBy(r.getCreatedBy());
        d.setLastActor(r.getLastActor());
        d.setPostedBy(r.getPostedBy());
        d.setPostedAt(r.getPostedAt());
        d.setVoidReason(r.getVoidReason());
        d.setCreatedAt(r.getCreatedAt());
        d.setUpdatedAt(r.getUpdatedAt());

        TemplateSchema schema = schemaOf(r);
        d.setTemplateVersion(r.getTemplateVersion());
        d.setFields(schema.fields());
        d.setBasis(readBasis(r));
        return d;
    }

    private java.util.List<com.example.ledger.common.Attachment> readAttachments(LedgerRecord r) {
        if (r.getAttachments() == null || r.getAttachments().isBlank()) {
            return java.util.List.of();
        }
        try {
            return objectMapper.readValue(r.getAttachments(),
                    new TypeReference<java.util.List<com.example.ledger.common.Attachment>>() {
                    });
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    private LedgerQuality readQuality(LedgerRecord r) {
        try {
            return objectMapper.readValue(r.getQualityDetail(), LedgerQuality.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> readBasis(LedgerRecord r) {
        if (r.getBasis() == null || r.getBasis().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(r.getBasis(), new TypeReference<HashMap<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<Long, String> categoryNames() {
        Map<Long, String> map = new HashMap<>();
        for (Category c : categoryMapper.selectList(null)) {
            map.put(c.getId(), c.getName());
        }
        return map;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(500, "序列化失败: " + e.getMessage());
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String trim(String s) {
        return s == null ? null : s.trim();
    }
}