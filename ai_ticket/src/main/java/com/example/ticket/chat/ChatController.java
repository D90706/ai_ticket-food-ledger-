package com.example.ticket.chat;

import com.example.ticket.chat.dto.ChatResponse;
import com.example.ticket.chat.dto.ConfirmRequest;
import com.example.ticket.chat.dto.CreateSessionRequest;
import com.example.ticket.chat.dto.CreateSessionResponse;
import com.example.ticket.chat.dto.MessageView;
import com.example.ticket.chat.dto.SendMessageRequest;
import com.example.ticket.chat.dto.TicketStatusView;
import com.example.ticket.common.ApiResponse;
import com.example.ticket.template.TemplateService;
import com.example.ticket.template.dto.TemplateResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 对话接口。普通 POST + JSON，没有流式。
 *
 * 不做流式的理由：实测端到端约 1 秒，模型输出是结构化 JSON，
 * 而顾客看到的两句话（追问、出单提示）都由代码瞬时生成。
 * 整条流式管道在这个形态下没有可服务的东西。
 */
@RestController
@RequestMapping("/api/chat/sessions")
@RequiredArgsConstructor
public class ChatController {

    private final SessionStore sessionStore;
    private final ConversationOrchestrator orchestrator;
    private final TemplateService templateService;
    private final ChatStatusService chatStatusService;

    @PostMapping
    public ApiResponse<CreateSessionResponse> create(
            @Valid @RequestBody CreateSessionRequest request) {
        TemplateResponse template = templateService.get(request.getTemplateId());
        SessionView session = sessionStore.create(template.getId(), template.getVersion());

        String greeting = "您好，这里是「" + template.getName()
                + "」，请描述您遇到的问题，我会为您整理成工单。";
        sessionStore.appendMessage(session.getId(), "assistant", greeting);

        CreateSessionResponse response = new CreateSessionResponse();
        response.setSessionId(session.getId());
        response.setTemplateName(template.getName());
        response.setGreeting(greeting);
        return ApiResponse.ok(response);
    }

    @PostMapping("/{sessionId}/messages")
    public ApiResponse<ChatResponse> send(@PathVariable Long sessionId,
                                          @Valid @RequestBody SendMessageRequest request) {
        return ApiResponse.ok(toResponse(orchestrator.handle(sessionId, request.getContent())));
    }

    /**
     * 顾客确认后提交给人工。只有「信息不全」的工单会走到这里。
     * summary 允许被顾客改过；不传就用系统整理的那一版。
     */
    @PostMapping("/{sessionId}/confirm")
    public ApiResponse<ChatResponse> confirm(@PathVariable Long sessionId,
                                             @RequestBody(required = false) ConfirmRequest request) {
        String summary = request == null ? null : request.getSummary();
        return ApiResponse.ok(toResponse(orchestrator.confirm(sessionId, summary)));
    }

    /**
     * 工单状态回执。顾客出单后轮询这里。
     *
     * 只有状态跨过节点时才会产生新回执（changed=true），
     * 所以前端可以放心地每几秒问一次，不会刷屏。
     * 还没出单时返回 null。
     */
    @GetMapping("/{sessionId}/status")
    public ApiResponse<TicketStatusView> status(@PathVariable Long sessionId) {
        return ApiResponse.ok(chatStatusService.check(sessionId));
    }

    @GetMapping("/{sessionId}/messages")
    public ApiResponse<List<MessageView>> history(@PathVariable Long sessionId) {
        sessionStore.load(sessionId);   // 不存在时由 load 抛 404
        List<MessageView> views = sessionStore.history(sessionId).stream()
                .map(m -> MessageView.of(m.getId(), m.getRole(), m.getContent()))
                .toList();
        return ApiResponse.ok(views);
    }

    // ---------------------------------------------------------- 结果映射

    private ChatResponse toResponse(OrchestratorResult result) {
        ChatResponse response = new ChatResponse();
        response.setReplyType(result.replyType().name());

        switch (result.replyType()) {
            case ASK -> {
                response.setQuestion(result.question());
                response.setFollowupCount(result.followupCount());
            }
            case CONFIRM -> {
                response.setSummary(result.confirmSummary());
                response.setMissing(result.missingLabels());
                // 刻意不回 level：RED 是我们内部的词汇，顾客只需要知道「还缺什么」。
                // 「信息不全」这件事由 missing 列表表达，不需要把分级体系暴露出去。
                // 提示语由后端给，前端不用复制一份文案（避免两边漂移）
                response.setMessages(List.of(ConversationOrchestrator.CONFIRM_PROMPT));
            }
            case TICKET -> {
                response.setTicketId(result.ticketId());
                response.setLevel(result.level());
                response.setMessages(result.messages());
                response.setResubmitted(result.resubmitted());
            }
        }
        return response;
    }
}
