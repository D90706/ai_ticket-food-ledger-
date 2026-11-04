package com.example.ledger.template;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ledger.common.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 模板读取。
 *
 * 模板是整个系统的 schema 来源：
 * 判定合规、渲染表单、决定哪些字段能删，都从这里拿，不各写一份。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final TemplateMapper templateMapper;
    private final ObjectMapper objectMapper;

    @Value("${app.ledger.default-shelf-life-warn-days:3}")
    private int defaultWarnDays = 3;

    /** 默认模板：启用的第一个。单模板场景够用，多模板再扩展。 */
    public TemplateSchema defaultSchema() {
        LedgerTemplate template = templateMapper.selectOne(
                Wrappers.<LedgerTemplate>lambdaQuery()
                        .eq(LedgerTemplate::getEnabled, true)
                        .orderByAsc(LedgerTemplate::getId)
                        .last("LIMIT 1"));
        if (template == null) {
            throw new BizException(500, "没有可用的台账模板，请先初始化数据");
        }
        return toSchema(template);
    }

    public TemplateSchema getSchema(Long id) {
        LedgerTemplate template = templateMapper.selectById(id);
        if (template == null) {
            throw BizException.notFound("台账模板");
        }
        return toSchema(template);
    }

    public List<LedgerTemplate> list() {
        return templateMapper.selectList(
                Wrappers.<LedgerTemplate>lambdaQuery().orderByAsc(LedgerTemplate::getId));
    }

    private TemplateSchema toSchema(LedgerTemplate template) {
        List<TemplateField> fields;
        try {
            fields = objectMapper.readValue(template.getFields(), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new BizException(500, "模板字段定义解析失败: " + e.getMessage());
        }
        int warnDays = template.getShelfLifeWarnDays() == null
                ? defaultWarnDays : template.getShelfLifeWarnDays();
        return new TemplateSchema(template.getId(), template.getName(),
                template.getVersion(), fields, warnDays);
    }
}