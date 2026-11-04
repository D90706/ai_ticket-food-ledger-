package com.example.ticket.chat;

import com.example.ticket.common.ApiResponse;
import com.example.ticket.template.TemplateService;
import com.example.ticket.template.dto.TemplateOption;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端入口的公共接口。
 *
 * 单独一个控制器，而不是塞进 ChatController：
 * ChatController 挂在 /api/chat/sessions 下，把方法放进去路径会变成
 * /api/chat/sessions/templates —— 读起来像「某个会话的模板」，语义是错的。
 *
 * 这里只暴露「能咨询哪几类问题」（id + 名称）。
 * 字段定义、决策项、质量明细一概不出去：
 * 顾客不需要知道后台是怎么配字段的，也不该看到内部分级。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatEntryController {

    private final TemplateService templateService;

    @GetMapping("/templates")
    public ApiResponse<List<TemplateOption>> templates() {
        return ApiResponse.ok(templateService.listEnabledOptions());
    }
}
