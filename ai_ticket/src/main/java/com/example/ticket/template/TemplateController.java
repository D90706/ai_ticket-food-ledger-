package com.example.ticket.template;

import com.example.ticket.common.ApiResponse;
import com.example.ticket.template.dto.TemplateResponse;
import com.example.ticket.template.dto.TemplateSummary;
import com.example.ticket.template.dto.TemplateUpsertRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模板接口。
 *
 * PUT 是整体覆盖——不提供字段级 CRUD。
 * 前端维护一份本地 json，改完整体提交。
 */
@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    @GetMapping
    public ApiResponse<List<TemplateSummary>> list() {
        return ApiResponse.ok(templateService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<TemplateResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(templateService.get(id));
    }

    @PostMapping
    public ApiResponse<TemplateResponse> create(@Valid @RequestBody TemplateUpsertRequest req) {
        return ApiResponse.ok(templateService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<TemplateResponse> update(@PathVariable Long id,
                                                @Valid @RequestBody TemplateUpsertRequest req) {
        return ApiResponse.ok(templateService.update(id, req));
    }
}
