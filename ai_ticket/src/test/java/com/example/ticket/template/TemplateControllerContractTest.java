package com.example.ticket.template;

import com.example.ticket.auth.AuthInterceptor;
import com.example.ticket.common.BizException;
import com.example.ticket.template.dto.TemplateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口契约测试：只验证「响应结构」与 mock/template.json 一致。
 *
 * 前端是按 mock 文件开发的，所以字段名一旦漂移，
 * 这个测试就该红。它守的是契约，不是业务。
 */
@WebMvcTest(TemplateController.class)
class TemplateControllerContractTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TemplateService templateService;

    /**
     * 这个契约测试只关心响应结构，不关心登录。
     * 把拦截器换成 mock 并放行，否则 /api/templates 会被 401 挡住。
     */
    @MockitoBean
    private AuthInterceptor authInterceptor;

    @BeforeEach
    void bypassAuth() throws Exception {
        when(authInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    private TemplateResponse sample() {
        TemplateResponse r = new TemplateResponse();
        r.setId(1L);
        r.setName("电商售后工单");
        r.setVersion(3);
        r.setEnabled(true);
        r.setRole("你是售后助手");
        r.setKnowledge("知识");
        r.setDecisionOptions(List.of("批准换货", "拒绝"));
        r.setFields(List.of(
                new FieldDef("order_id", "订单号", FieldType.TEXT, true, null, "^\\d{8,20}$"),
                new FieldDef("reason", "原因", FieldType.ENUM, true,
                        List.of("尺码不合适", "质量问题"), null)));
        return r;
    }

    @Test
    @DisplayName("GET /api/templates/{id} 的响应结构与 mock 一致")
    void detailMatchesContract() throws Exception {
        when(templateService.get(anyLong())).thenReturn(sample());

        mvc.perform(get("/api/templates/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.role").value("你是售后助手"))
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.decisionOptions[0]").value("批准换货"))
                // 关键：字段类型必须序列化成小写字符串，前端按这个判断控件
                .andExpect(jsonPath("$.data.fields[0].type").value("text"))
                .andExpect(jsonPath("$.data.fields[0].required").value(true))
                .andExpect(jsonPath("$.data.fields[0].pattern").value("^\\d{8,20}$"))
                .andExpect(jsonPath("$.data.fields[1].type").value("enum"))
                .andExpect(jsonPath("$.data.fields[1].options[1]").value("质量问题"))
                .andExpect(jsonPath("$.data.fields[1].pattern").doesNotExist());
    }

    @Test
    @DisplayName("业务异常统一返回 HTTP 200 + 业务 code")
    void bizExceptionKeepsHttp200() throws Exception {
        when(templateService.get(anyLong()))
                .thenThrow(new BizException(404, "模板 9 不存在"));

        mvc.perform(get("/api/templates/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("模板 9 不存在"));
    }

    @Test
    @DisplayName("POST 缺 name 时被参数校验拦下")
    void createValidatesBody() throws Exception {
        String body = """
                {"role":"你是售后助手","decisionOptions":["批准换货"],
                 "fields":[{"key":"k","label":"x","type":"text","required":true}]}
                """;
        mvc.perform(post("/api/templates")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("services 抛出的校验异常也走统一响应")
    void createPropagatesBizError() throws Exception {
        when(templateService.create(any()))
                .thenThrow(new BizException("字段 order_id 必须显式声明必填或选填（required 不能为空）"));

        String body = """
                {"name":"t","role":"r","decisionOptions":["a"],
                 "fields":[{"key":"order_id","label":"订单号","type":"text"}]}
                """;
        mvc.perform(post("/api/templates")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("显式声明")));
    }
}
