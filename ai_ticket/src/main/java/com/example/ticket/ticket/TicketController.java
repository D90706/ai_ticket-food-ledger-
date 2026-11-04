package com.example.ticket.ticket;

import com.example.ticket.auth.CurrentAgent;
import com.example.ticket.common.ApiResponse;
import com.example.ticket.ticket.dto.TicketDetail;
import com.example.ticket.ticket.dto.TicketPatchRequest;
import com.example.ticket.ticket.dto.TicketSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工单接口。
 *
 * 没有 /conversation 端点：原始对话通过
 * GET /api/chat/sessions/{sessionId}/messages 获取，
 * sessionId 在工单详情里。
 *
 * 这样切是为了不产生 ticket → chat 的包依赖——
 * 依赖方向是 chat → ticket（编排层触发落库），反向会成环。
 */
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;
    private final CurrentAgent currentAgent;

    @GetMapping
    public ApiResponse<List<TicketSummary>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String level) {
        return ApiResponse.ok(ticketService.list(status, level));
    }

    @GetMapping("/{id}")
    public ApiResponse<TicketDetail> get(@PathVariable Long id) {
        return ApiResponse.ok(ticketService.get(id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<TicketDetail> patch(@PathVariable Long id,
                                           @RequestBody TicketPatchRequest request) {
        // 审计人来自会话，不由前端传——前端能传就能伪造
        return ApiResponse.ok(ticketService.patch(id, request, currentAgent.displayNameOrNull()));
    }
}
