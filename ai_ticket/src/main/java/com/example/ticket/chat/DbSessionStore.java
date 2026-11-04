package com.example.ticket.chat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ticket.common.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SessionStore 的数据库实现。
 *
 * JSON 列在这里完成与 Map 的互转，编排层看不到 String。
 * 转换失败不抛原始异常，而是换成一句能看懂的提示——
 * 这类错误几乎都意味着数据被外部改坏了。
 */
@Component
@RequiredArgsConstructor
public class DbSessionStore implements SessionStore {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    @Override
    public SessionView create(Long templateId, int templateVersion) {
        LocalDateTime now = LocalDateTime.now();
        ChatSession entity = new ChatSession();
        entity.setTemplateId(templateId);
        entity.setTemplateVersion(templateVersion);
        entity.setState(SessionState.CHATTING.name());
        entity.setFollowupCount(0);
        entity.setCollectedValuesJson("{}");
        entity.setCollectedBasisJson("{}");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        sessionMapper.insert(entity);

        SessionView view = new SessionView();
        view.setId(entity.getId());
        view.setTemplateId(templateId);
        view.setTemplateVersion(templateVersion);
        return view;
    }

    @Override
    public SessionView load(Long sessionId) {
        ChatSession entity = sessionMapper.selectById(sessionId);
        if (entity == null) {
            throw BizException.notFound("会话 " + sessionId + " ");
        }
        SessionView view = new SessionView();
        view.setId(entity.getId());
        view.setTemplateId(entity.getTemplateId());
        view.setTemplateVersion(entity.getTemplateVersion() == null
                ? 1 : entity.getTemplateVersion());
        view.setFollowupCount(entity.getFollowupCount() == null ? 0 : entity.getFollowupCount());
        view.setState(SessionState.valueOf(entity.getState()));
        view.setDraftSummary(entity.getDraftSummary());
        view.setLastNotifiedStatus(entity.getLastNotifiedStatus());
        view.setTicketId(entity.getTicketId());
        view.setValues(readMap(entity.getCollectedValuesJson()));
        view.setBasis(readBasis(entity.getCollectedBasisJson()));
        return view;
    }

    @Override
    public void save(SessionView view) {
        ChatSession entity = new ChatSession();
        entity.setId(view.getId());
        entity.setState(view.getState().name());
        entity.setFollowupCount(view.getFollowupCount());
        entity.setCollectedValuesJson(write(view.getValues()));
        entity.setCollectedBasisJson(write(view.getBasis()));
        entity.setDraftSummary(view.getDraftSummary());
        entity.setLastNotifiedStatus(view.getLastNotifiedStatus());
        entity.setTicketId(view.getTicketId());
        entity.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(entity);
    }

    @Override
    public void appendMessage(Long sessionId, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
    }

    @Override
    public List<ChatMessage> history(Long sessionId) {
        return messageMapper.selectList(Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getId));
    }

    // ---------------------------------------------------------- JSON

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new BizException(500, "会话数据损坏，无法解析: " + e.getMessage());
        }
    }

    private Map<String, String> readBasis(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (Exception e) {
            throw new BizException(500, "会话依据数据损坏，无法解析: " + e.getMessage());
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(500, "会话数据序列化失败: " + e.getMessage());
        }
    }
}
