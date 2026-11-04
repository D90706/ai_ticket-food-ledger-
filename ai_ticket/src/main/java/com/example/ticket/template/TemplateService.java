package com.example.ticket.template;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ticket.common.BizException;
import com.example.ticket.template.dto.TemplateResponse;
import com.example.ticket.template.dto.TemplateOption;
import com.example.ticket.template.dto.TemplateSummary;
import com.example.ticket.template.dto.TemplateUpsertRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 模板模块的全部业务。
 *
 * 职责边界（design-modules.md §七）：
 *   做：schema 的存取、校验、版本递增、转成 TemplateSchema
 *   不做：抽取、评分、落库以外的任何编排
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final TemplateMapper templateMapper;
    private final ObjectMapper objectMapper;

    // ---------------------------------------------------------- 查询

    public List<TemplateSummary> list() {
        List<Template> rows = templateMapper.selectList(
                Wrappers.<Template>lambdaQuery().orderByDesc(Template::getId));
        return rows.stream().map(this::toSummary).toList();
    }

    public TemplateResponse get(Long id) {
        return toResponse(require(id));
    }

    /**
     * 面向用户端的选项列表：只返回已启用的模板 id 与名称。
     *
     * 单独开一个方法而不是过滤 list()：
     * 用户端不该拿到字段数、版本、更新时间这些后台配置的轮廓。
     */
    public List<TemplateOption> listEnabledOptions() {
        List<Template> rows = templateMapper.selectList(
                Wrappers.<Template>lambdaQuery()
                        .eq(Template::getEnabled, true)
                        .orderByAsc(Template::getId));
        return rows.stream().map(t -> {
            TemplateOption option = new TemplateOption();
            option.setId(t.getId());
            option.setName(t.getName());
            return option;
        }).toList();
    }

    /**
     * 供抽取层与核验层使用。它们只认识 TemplateSchema，不认识实体。
     */
    public TemplateSchema getSchema(Long id) {
        return toSchema(require(id));
    }

    // ---------------------------------------------------------- 写入

    @Transactional
    public TemplateResponse create(TemplateUpsertRequest req) {
        validate(req);
        LocalDateTime now = LocalDateTime.now();

        Template entity = new Template();
        entity.setName(req.getName().trim());
        entity.setVersion(1);
        entity.setRoleText(req.getRole().trim());
        entity.setKnowledge(req.getKnowledge());
        entity.setDecisionOptionsJson(writeJson(req.getDecisionOptions()));
        entity.setFieldsJson(writeJson(req.getFields()));
        entity.setEnabled(Boolean.TRUE);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        templateMapper.insert(entity);
        log.info("新建模板 id={} name={} 字段数={}", entity.getId(), entity.getName(),
                req.getFields().size());
        return toResponse(entity);
    }

    /**
     * 整体覆盖，version 每次 +1。
     * 版本号不是为了留历史（历史渲染靠工单里的 schema_snapshot），
     * 而是为了标记「这张工单是按第几版 schema 生成的」。
     */
    @Transactional
    public TemplateResponse update(Long id, TemplateUpsertRequest req) {
        validate(req);
        Template entity = require(id);

        entity.setName(req.getName().trim());
        entity.setRoleText(req.getRole().trim());
        entity.setKnowledge(req.getKnowledge());
        entity.setDecisionOptionsJson(writeJson(req.getDecisionOptions()));
        entity.setFieldsJson(writeJson(req.getFields()));
        entity.setVersion(entity.getVersion() + 1);
        entity.setUpdatedAt(LocalDateTime.now());

        templateMapper.updateById(entity);
        log.info("更新模板 id={} 新版本={} 字段数={}", id, entity.getVersion(),
                req.getFields().size());
        return toResponse(entity);
    }

    // ---------------------------------------------------------- 校验

    /**
     * 模板保存前的校验。
     * 这里挡住的都是「会让后面模块出错」的问题，不是格式洁癖。
     */
    private void validate(TemplateUpsertRequest req) {
        if (isBlank(req.getName())) {
            throw new BizException("模板名称不能为空");
        }
        if (isBlank(req.getRole())) {
            throw new BizException("role 不能为空——AI 必须知道自己在扮演谁");
        }
        if (req.getDecisionOptions() == null || req.getDecisionOptions().isEmpty()) {
            throw new BizException("决策项至少需要一项，否则 AI 只能给出自由文本建议");
        }
        if (req.getFields() == null || req.getFields().isEmpty()) {
            throw new BizException("字段至少需要一项");
        }

        Set<String> keys = new HashSet<>();
        for (FieldDef f : req.getFields()) {
            if (isBlank(f.getKey())) {
                throw new BizException("存在未命名的字段（key 为空）");
            }
            if (!keys.add(f.getKey())) {
                throw new BizException("字段 key 重复: " + f.getKey());
            }
            if (f.getType() == null) {
                throw new BizException("字段 " + f.getKey() + " 未指定类型");
            }
            // 设计规则：必填/选填必须显式声明，不允许默认
            if (f.getRequired() == null) {
                throw new BizException("字段 " + f.getKey()
                        + " 必须显式声明必填或选填（required 不能为空）");
            }
            if (f.getType() == FieldType.ENUM
                    && (f.getOptions() == null || f.getOptions().isEmpty())) {
                throw new BizException("枚举字段 " + f.getKey() + " 至少需要一个候选值");
            }
            if (f.hasPattern()) {
                if (!f.getType().supportsPattern()) {
                    throw new BizException("字段 " + f.getKey() + " 的类型是 "
                            + f.getType().getCode() + "，只有 text 支持 pattern");
                }
                try {
                    Pattern.compile(f.getPattern());
                } catch (PatternSyntaxException e) {
                    throw new BizException("字段 " + f.getKey() + " 的正则不合法: "
                            + e.getDescription());
                }
            }
        }
    }

    // ---------------------------------------------------------- 转换

    private Template require(Long id) {
        Template entity = templateMapper.selectById(id);
        if (entity == null) {
            throw BizException.notFound("模板 " + id + " ");
        }
        return entity;
    }

    public TemplateSchema toSchema(Template entity) {
        return new TemplateSchema(
                entity.getName(),
                entity.getVersion(),
                entity.getRoleText(),
                entity.getKnowledge(),
                readStringList(entity.getDecisionOptionsJson()),
                readFieldList(entity.getFieldsJson()));
    }

    private TemplateResponse toResponse(Template entity) {
        TemplateResponse r = new TemplateResponse();
        r.setId(entity.getId());
        r.setName(entity.getName());
        r.setVersion(entity.getVersion());
        r.setEnabled(entity.getEnabled());
        r.setRole(entity.getRoleText());
        r.setKnowledge(entity.getKnowledge());
        r.setDecisionOptions(readStringList(entity.getDecisionOptionsJson()));
        r.setFields(readFieldList(entity.getFieldsJson()));
        r.setCreatedAt(entity.getCreatedAt());
        r.setUpdatedAt(entity.getUpdatedAt());
        return r;
    }

    private TemplateSummary toSummary(Template entity) {
        TemplateSummary s = new TemplateSummary();
        s.setId(entity.getId());
        s.setName(entity.getName());
        s.setVersion(entity.getVersion());
        s.setEnabled(entity.getEnabled());
        s.setFieldCount(readFieldList(entity.getFieldsJson()).size());
        s.setUpdatedAt(entity.getUpdatedAt());
        return s;
    }

    // ---------------------------------------------------------- JSON

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(500, "模板序列化失败: " + e.getMessage());
        }
    }

    /** 空值一律按空集合处理，避免调用方到处判空 */
    private List<String> readStringList(String json) {
        if (isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new BizException(500, "模板的决策项数据损坏: " + e.getMessage());
        }
    }

    private List<FieldDef> readFieldList(String json) {
        if (isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<FieldDef>>() {
            });
        } catch (Exception e) {
            throw new BizException(500, "模板的字段定义数据损坏: " + e.getMessage());
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
