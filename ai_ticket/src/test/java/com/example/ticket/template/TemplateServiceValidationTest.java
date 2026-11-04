package com.example.ticket.template;

import com.example.ticket.common.BizException;
import com.example.ticket.template.dto.TemplateUpsertRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 模板保存校验的纯单元测试。
 *
 * 不启动 Spring、不连数据库：校验全部发生在 mapper 调用之前，
 * 所以 mapper 用 mock 就够。
 *
 * 这些规则挡的都是「会让后面模块出错」的问题，
 * 最典型的一条是 required 必须显式声明——
 * 它是「必填/选填不允许默认」这条设计规则的代码落点。
 */
class TemplateServiceValidationTest {

    private final TemplateMapper mapper = mock(TemplateMapper.class);
    private final TemplateService service = new TemplateService(mapper, new ObjectMapper());

    private TemplateUpsertRequest base() {
        TemplateUpsertRequest req = new TemplateUpsertRequest();
        req.setName("电商售后工单");
        req.setRole("你是售后助手");
        req.setKnowledge("知识");
        req.setDecisionOptions(List.of("批准换货", "拒绝"));
        req.setFields(List.of(
                new FieldDef("order_id", "订单号", FieldType.TEXT, true, null, "^\\d{8,20}$")));
        return req;
    }

    private void expectFail(TemplateUpsertRequest req, String keyword) {
        BizException e = assertThrows(BizException.class, () -> service.create(req));
        assertTrue(e.getMessage().contains(keyword),
                "期望错误信息包含「" + keyword + "」，实际: " + e.getMessage());
    }

    @Test
    @DisplayName("合法模板可以通过校验")
    void validTemplatePasses() {
        when(mapper.insert(any(Template.class))).thenReturn(1);
        assertDoesNotThrow(() -> service.create(base()));
    }

    @Test
    @DisplayName("名称与 role 不能为空")
    void blankNameOrRole() {
        TemplateUpsertRequest a = base();
        a.setName("  ");
        expectFail(a, "模板名称");

        TemplateUpsertRequest b = base();
        b.setRole(null);
        expectFail(b, "role");
    }

    @Test
    @DisplayName("决策项与字段不能为空")
    void emptyOptionsOrFields() {
        TemplateUpsertRequest a = base();
        a.setDecisionOptions(List.of());
        expectFail(a, "决策项");

        TemplateUpsertRequest b = base();
        b.setFields(List.of());
        expectFail(b, "字段至少");
    }

    @Test
    @DisplayName("字段 key 不能为空、不能重复")
    void blankOrDuplicateKey() {
        TemplateUpsertRequest a = base();
        a.setFields(List.of(new FieldDef("  ", "x", FieldType.TEXT, true, null, null)));
        expectFail(a, "未命名");

        TemplateUpsertRequest b = base();
        b.setFields(List.of(
                new FieldDef("k", "a", FieldType.TEXT, true, null, null),
                new FieldDef("k", "b", FieldType.TEXT, false, null, null)));
        expectFail(b, "重复");
    }

    @Test
    @DisplayName("required 必须显式声明，null 一律拒绝")
    void requiredMustBeExplicit() {
        TemplateUpsertRequest req = base();
        req.setFields(List.of(
                new FieldDef("order_id", "订单号", FieldType.TEXT, null, null, null)));
        expectFail(req, "显式声明");
    }

    @Test
    @DisplayName("枚举字段必须有候选值")
    void enumNeedsOptions() {
        TemplateUpsertRequest req = base();
        req.setFields(List.of(
                new FieldDef("reason", "原因", FieldType.ENUM, true, List.of(), null)));
        expectFail(req, "候选值");
    }

    @Test
    @DisplayName("pattern 只能配在 text 上，且必须是合法正则")
    void patternRules() {
        TemplateUpsertRequest a = base();
        a.setFields(List.of(
                new FieldDef("num", "数量", FieldType.NUMBER, true, null, "^\\d+$")));
        expectFail(a, "只有 text");

        TemplateUpsertRequest b = base();
        b.setFields(List.of(
                new FieldDef("code", "编码", FieldType.TEXT, true, null, "[0-9")));
        expectFail(b, "正则不合法");
    }
}
