package com.example.ledger.entry;

import com.example.ledger.auth.CurrentStaff;
import com.example.ledger.common.ApiResponse;
import com.example.ledger.common.Attachment;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 录入接口（采购录入端用）。
 *
 * 它只产出「待入账」记录，入账仍然是管理员的动作。
 */
@RestController
@RequestMapping("/api/entry")
@RequiredArgsConstructor
public class EntryController {

    private final EntryService entryService;
    private final CurrentStaff currentStaff;

    @PostMapping("/sessions")
    public ApiResponse<CreateResponse> create() {
        EntrySessionView session = entryService.create(actor());
        CreateResponse response = new CreateResponse();
        response.setSessionId(session.getId());
        return ApiResponse.ok(response);
    }

    @PostMapping("/sessions/{id}/messages")
    public ApiResponse<EntryResponse> send(@PathVariable Long id,
                                          @RequestBody MessageBody body) {
        return ApiResponse.ok(toResponse(entryService.handle(id, body.getContent(), actor())));
    }

    /**
     * 对话中途上传凭证照片。
     *
     * 前端先把文件传到 /api/uploads 拿到 {url,name,size}，再把这张「票」挂到这次录入上。
     * 拆成两步是为了让上传逻辑只有一份（附件白名单、大小限制、文件名生成都在上传口）。
     */
    @PostMapping("/sessions/{id}/attachments")
    public ApiResponse<EntryResponse> attach(@PathVariable Long id,
                                            @RequestBody AttachmentBody body) {
        Attachment attachment = new Attachment();
        attachment.setUrl(body.getUrl());
        attachment.setName(body.getName());
        attachment.setSize(body.getSize());
        return ApiResponse.ok(toResponse(entryService.attach(id, attachment, actor())));
    }

    @lombok.Data
    public static class AttachmentBody {
        private String url;
        private String name;
        private Long size;
    }

    @PostMapping("/sessions/{id}/confirm")
    public ApiResponse<EntryResponse> confirm(@PathVariable Long id,
                                             @RequestBody(required = false) ConfirmBody body) {
        String summary = body == null ? null : body.getSummary();
        return ApiResponse.ok(toResponse(entryService.confirm(id, summary, actor())));
    }

    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<List<Map<String, Object>>> history(@PathVariable Long id) {
        List<Map<String, Object>> views = entryService.history(id).stream()
                .map(m -> Map.<String, Object>of(
                        "id", m.getId(),
                        "role", m.getRole(),
                        "content", m.getContent(),
                        "createdAt", m.getCreatedAt() == null ? "" : m.getCreatedAt().toString()))
                .toList();
        return ApiResponse.ok(views);
    }

    private EntryResponse toResponse(EntryResult result) {
        EntryResponse r = new EntryResponse();
        r.setReplyType(result.replyType().name());
        switch (result.replyType()) {
            case ASK -> {
                r.setQuestion(result.question());
                r.setFollowupCount(result.followupCount());
            }
            case CONFIRM -> {
                r.setSummary(result.summary());
                r.setMissing(result.missingLabels());
            }
            case RECORD -> {
                r.setRecordId(result.recordId());
                r.setRecordStatus(result.recordStatus());
                r.setLevel(result.level());
            }
        }
        return r;
    }

    private String actor() {
        return currentStaff.require().getDisplayName();
    }

    // ---------------------------------------------------------- 请求/响应

    @Data
    public static class CreateResponse {
        private Long sessionId;
    }

    @Data
    public static class MessageBody {
        private String content;
    }

    @Data
    public static class ConfirmBody {
        private String summary;
    }

    @Data
    public static class EntryResponse {
        private String replyType;
        private String question;
        private Integer followupCount;
        private String summary;
        private List<String> missing;
        private Long recordId;
        private String recordStatus;
        private String level;
    }
}