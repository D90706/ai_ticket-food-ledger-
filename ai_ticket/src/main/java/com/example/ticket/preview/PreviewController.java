package com.example.ticket.preview;

import com.example.ticket.common.ApiResponse;
import com.example.ticket.preview.dto.PreviewRequest;
import com.example.ticket.preview.dto.PreviewResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 试抽接口。路径挂在模板下（模板作者在编辑模板时用），
 * 但代码归属 preview 包——避免 template 反依赖 extraction。
 */
@RestController
@RequestMapping("/api/templates/{templateId}/preview")
@RequiredArgsConstructor
public class PreviewController {

    private final PreviewService previewService;

    @PostMapping
    public ApiResponse<PreviewResponse> preview(
            @PathVariable Long templateId,
            @Valid @RequestBody PreviewRequest request) {
        return ApiResponse.ok(previewService.preview(templateId, request.getDialogue()));
    }
}
