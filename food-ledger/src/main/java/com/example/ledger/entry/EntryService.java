package com.example.ledger.entry;

import com.example.ledger.common.Attachment;
import com.example.ledger.common.BizException;
import com.example.ledger.record.dto.LedgerDetail;
import com.example.ledger.record.ComplianceEvaluator;
import com.example.ledger.record.LedgerQuality;
import com.example.ledger.record.LedgerService;
import com.example.ledger.record.LedgerValueMapper;
import com.example.ledger.record.dto.LedgerUpsertRequest;
import com.example.ledger.template.TemplateField;
import com.example.ledger.template.TemplateSchema;
import com.example.ledger.template.TemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 录入的编排：一句话 → 抽取 → 判定 → 追问 / 确认 / 生成台账记录。
 *
 * 最要紧的一条：
 *   **AI 只生成「待入账」记录，绝不直接入账。**
 *   台账是给监管看的东西，一条模型幻觉进去就是合规事故。
 *   人必须在入账这一步上按一次键——这是设计，不是偷懒。
 *
 * 追问上限的存在理由：必填项并不总是能够获得的
 * （散装蔬菜没有批号）。没有上限，这张记录会永远卡在追问里，
 * 采购员既记不了账，也不知道该怎么办。超限就转「确认」，
 * 让人看一眼缺什么就提交，缺口显式暴露，而不是静默失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntryService {

    private final EntryStore store;
    private final TemplateService templateService;
    private final Extractor extractor;
    private final FollowUpBuilder followUpBuilder;
    private final LedgerService ledgerService;
    private final LedgerValueMapper valueMapper;

    @Value("${app.entry.max-followups:2}")
    private int maxFollowups = 2;

    @Value("${app.entry.confirm-before-record:true}")
    private boolean confirmBeforeRecord = true;

    // ============================================================ 会话

    public EntrySessionView create(String actor) {
        TemplateSchema schema = templateService.defaultSchema();
        EntrySessionView session = store.create(schema.templateId(), schema.version(), actor);
        store.appendMessage(session.getId(), "assistant",
                "您好，请用一句话说说这笔采购：什么货、多少、谁供的、什么时候进的。"
                        + "说全了我直接记账，说不全我再问您。");
        return session;
    }

    public List<EntryMessage> history(Long sessionId) {
        store.load(sessionId);   // 不存在时抛 404
        return store.history(sessionId);
    }

    /**
     * 对话中途上传凭证照片。
     *
     * 为什么要在这个位置做：追问里点名的「索证索票凭证」，
     * 现实中是一张许可证或检疫证明的照片，不是一个编号。
     * 只让采购员打字填编号，等于要求他把凭证"抄"过来——
     * 抄过来的字不是凭证，不能替代检查时被要求出示原件。
     *
     * 上传后：附件进会话的 values，凭证这一项即视为已提供（判定规则见 ComplianceEvaluator），
     * 追问列表随之变化，不必等整轮结束。
     */
    public EntryResult attach(Long sessionId, Attachment attachment, String actor) {
        EntrySessionView session = store.load(sessionId);
        if ("RECORDED".equals(session.getState())) {
            throw new BizException(409, "这次录入已经生成台账记录，请开始新的录入");
        }
        List<Map<String, Object>> files = new ArrayList<>();
        Object existing = session.getValues().get("attachments");
        if (existing instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) m;
                    files.add(cast);
                }
            }
        }
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("url", attachment.getUrl());
        file.put("name", attachment.getName());
        file.put("size", attachment.getSize());
        files.add(file);

        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("attachments", files);
        session.merge(patch, Map.of());

        store.appendMessage(sessionId, "user", "（上传了凭证照片：" + attachment.getName() + "）");

        TemplateSchema schema = templateService.getSchema(session.getTemplateId());
        List<TemplateField> missing = requiredMissing(schema, session.getValues());
        if (missing.isEmpty()) {
            /*
             * 凭证补齐、其他也齐了：直接出单。
             *
             * 这里曾经回一句「信息齐了，确认一下就记账？」然后走 ASK——
             * 但 ASK 不会把会话置为 AWAITING_CONFIRM，前端再点确认会被拒，
             * 采购员就被卡在一句问话前面，无处可点。
             * 「说全了就直接记账」是这个会话从一开始的约定，补张照片也该照此办理。
             */
            store.appendMessage(sessionId, "assistant", "凭证已收到，信息齐了，正在记账。");
            log.info("会话 {} 上传凭证后信息齐全，直接出单", sessionId);
            return record(session, schema, null, actor);
        }
        String question = "凭证已收到。还差：" + String.join("、", labelsOf(missing)) + "。";
        store.appendMessage(sessionId, "assistant", question);
        store.save(session);
        log.info("会话 {} 上传凭证 {}，剩余缺失 {} 项", sessionId, attachment.getName(), missing.size());
        return EntryResult.ask(question, session.getFollowupCount());
    }

    // ============================================================ 一轮对话

    public EntryResult handle(Long sessionId, String content, String actor) {
        if (content == null || content.isBlank()) {
            throw new BizException("内容不能为空");
        }

        EntrySessionView session = store.load(sessionId);
        if ("RECORDED".equals(session.getState())) {
            throw new BizException(409, "这次录入已经生成台账记录，请开始新的录入");
        }
        // 待确认时又说话：当作补充，回到对话中重新抽取
        if ("AWAITING_CONFIRM".equals(session.getState())) {
            session.setState("CHATTING");
            session.setDraftSummary(null);
        }

        store.appendMessage(sessionId, "user", content);
        String dialogue = store.dialogueOf(sessionId);
        TemplateSchema schema = templateService.getSchema(session.getTemplateId());

        ExtractionResult extraction = extractor.extract(schema, dialogue, LocalDate.now());
        session.merge(extraction.getValues(), extraction.getBasis());

        List<TemplateField> missing = requiredMissing(schema, session.getValues());

        if (!missing.isEmpty()) {
            if (session.getFollowupCount() < maxFollowups) {
                String question = followUpBuilder.build(missing, session.getFollowupCount());
                session.setFollowupCount(session.getFollowupCount() + 1);
                store.appendMessage(sessionId, "assistant", question);
                store.save(session);
                return EntryResult.ask(question, session.getFollowupCount());
            }
            if (confirmBeforeRecord) {
                String summary = summaryOf(extraction.getSummary(), schema, session);
                session.setState("AWAITING_CONFIRM");
                session.setDraftSummary(summary);
                store.appendMessage(sessionId, "assistant", followUpBuilder.confirmPrompt());
                store.save(session);
                log.info("会话 {} 追问超限（缺 {} 项），转待确认", sessionId, missing.size());
                return EntryResult.confirm(summary, labelsOf(missing));
            }
            log.info("会话 {} 追问超限，直接生成待入账记录", sessionId);
        }

        return record(session, schema, extraction.getSummary(), actor);
    }

    /** 采购员点了确认：可以带一句他改过的概括 */
    public EntryResult confirm(Long sessionId, String editedSummary, String actor) {
        EntrySessionView session = store.load(sessionId);
        if (!"AWAITING_CONFIRM".equals(session.getState())) {
            throw new BizException(409, "当前不在待确认状态，无需确认");
        }
        TemplateSchema schema = templateService.getSchema(session.getTemplateId());
        String summary = (editedSummary == null || editedSummary.isBlank())
                ? session.getDraftSummary()
                : editedSummary.trim();
        return record(session, schema, summary, actor);
    }

    // ============================================================ 落库

    private EntryResult record(EntrySessionView session, TemplateSchema schema,
                               String summary, String actor) {
        LedgerUpsertRequest request = valueMapper.toRequest(schema, session.getValues());
        if (request.getIngredientName() == null || request.getIngredientName().isBlank()) {
            throw new BizException(400, "还没有识别出食材名称，请先说明是什么货");
        }

        LedgerDetail detail = ledgerService.create(request, actor, "AI",
                session.getId(), summaryOf(summary, schema, session), session.getBasis());

        session.setState("RECORDED");
        session.setRecordId(detail.getId());
        session.setDraftSummary(null);
        store.appendMessage(session.getId(), "assistant",
                followUpBuilder.recordNotice(detail.getStatusLabel(), detail.getId()));
        store.save(session);

        log.info("AI 录入生成台账记录 id={} 级别={} 状态={}",
                detail.getId(), detail.getQualityLevel(), detail.getStatus());
        return EntryResult.record(detail.getId(), detail.getStatus(),
                detail.getQualityLevel());
    }

    // ============================================================ 值转换

    // ============================================================ 工具

    private List<TemplateField> requiredMissing(TemplateSchema schema, Map<String, Object> values) {
        // 直接用合规判定算「还缺什么」，不要在这里再写一套。
        // 两套规则的后果很具体：判定知道「传了凭证照片就算有凭证」，
        // 追问这边却还在一遍遍问「请提供索证索票凭证」，采购员刚传完就被问一次。
        java.util.Set<String> missingKeys = new ComplianceEvaluator(schema)
                .evaluate(values, LocalDate.now())
                .getMissing().stream()
                .filter(LedgerQuality.Missing::isRequired)
                .map(LedgerQuality.Missing::getKey)
                .collect(java.util.stream.Collectors.toSet());
        List<TemplateField> missing = new ArrayList<>();
        for (TemplateField field : schema.fields()) {
            if (!field.isRequired()) {
                continue;
            }
            if (!missingKeys.contains(field.getKey())) {
                continue;
            }
            missing.add(field);
        }
        return missing;
    }

    private List<String> labelsOf(List<TemplateField> fields) {
        return fields.stream().map(TemplateField::getLabel).toList();
    }

    /**
     * 概括缺失时的兜底：用已抽到的字段拼一句。
     * 不是编造，只是把已经抽到的值摆成人能读的一句话，
     * 让确认卡上有东西可核对。
     */
    private String summaryOf(String summary, TemplateSchema schema, EntrySessionView session) {
        if (summary != null && !summary.isBlank()) {
            return summary.trim();
        }
        List<String> parts = new ArrayList<>();
        for (TemplateField field : schema.fields()) {
            Object value = session.getValues().get(field.getKey());
            if (value == null) {
                continue;
            }
            if (field.getKey().equals("ingredient_name")
                    || field.getKey().equals("quantity")
                    || field.getKey().equals("unit")
                    || field.getKey().equals("supplier_name")) {
                parts.add(String.valueOf(value));
            }
        }
        return parts.isEmpty() ? "（没有整理出有效信息，请补充）" : String.join(" ", parts);
    }
}
