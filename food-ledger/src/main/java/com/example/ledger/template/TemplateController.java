package com.example.ledger.template;

import com.example.ledger.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模板查询。
 *
 * 前端渲染**手填表单**要靠它：字段定义只有一个来源，
 * 前端不该把 19 个字段写死在代码里——那样改模板就得改前端。
 */
@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @GetMapping("/default")
    public ApiResponse<TemplateSchema> defaultTemplate() {
        return ApiResponse.ok(templateService.defaultSchema());
    }
}