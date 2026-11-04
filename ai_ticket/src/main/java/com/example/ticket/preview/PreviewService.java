package com.example.ticket.preview;

import com.example.ticket.common.BizException;
import com.example.ticket.extraction.ExtractionResult;
import com.example.ticket.extraction.Extractor;
import com.example.ticket.quality.QualityEvaluator;
import com.example.ticket.quality.QualityReport;
import com.example.ticket.preview.dto.PreviewResponse;
import com.example.ticket.template.TemplateSchema;
import com.example.ticket.template.TemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;

/**
 * 试抽：跑一次完整的抽取 + 核验，但不落库。
 *
 * 为什么这个接口值得优先实现：
 * 字段设计是这套系统里唯一需要业务知识的环节，
 * 而「我设计的字段在这段对话上抽出来长什么样」是没法靠脑补的。
 * 有了试抽，字段设计从盲猜变成可试错——
 * 这正是 idea.txt 6.2 那三个检验能真正落地的前提。
 *
 * 它同时是最省事的调试工具：一条命令就能看到
 * prompt 拼装、模型调用、结构校验、三维核验四条链路是否都活着。
 *
 * 它与正式出单共用同一套 Extractor 与 QualityEvaluator，
 * 所以试抽看到的结果就是真出单时会得到的结果（唯一差别是不写库）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreviewService {

    private final TemplateService templateService;
    private final Extractor extractor;
    private final QualityEvaluator qualityEvaluator;

    public PreviewResponse preview(Long templateId, String dialogue) {
        if (dialogue == null || dialogue.isBlank()) {
            throw new BizException("对话内容不能为空");
        }
        TemplateSchema schema = templateService.getSchema(templateId);

        ExtractionResult extraction = extractor.extract(schema, dialogue);
        QualityReport quality = qualityEvaluator.evaluate(
                schema,
                extraction.getValues(),
                extraction.getBasis(),
                extraction.getSummary(),
                dialogue,
                LocalDateTime.now());

        PreviewResponse response = new PreviewResponse();
        response.setDialogue(dialogue);
        response.setValues(new LinkedHashMap<>(extraction.getValues()));
        response.setBasis(new LinkedHashMap<>(extraction.getBasis()));
        response.setSummary(extraction.getSummary());
        response.setQuality(quality);

        // 与编排层同一条规则：缺必填时丢弃模型建议。
        // 试抽必须和真实出单表现一致，否则模板作者会被误导。
        boolean missingRequired = !quality.getDecision().getMissingRequired().isEmpty();
        response.setSuggestion(missingRequired ? null : extraction.getSuggestion());

        log.debug("试抽模板 {} 级别={} 缺必填={}", templateId,
                quality.getLevel(), quality.getDecision().getMissingRequired());
        return response;
    }
}
