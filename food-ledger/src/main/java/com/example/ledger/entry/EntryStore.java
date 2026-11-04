package com.example.ledger.entry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.ledger.common.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话存取。
 *
 * 为什么单独一层：编排逻辑不该关心"值存在 JSON 还是列里"。
 * 以后换 Redis 存会话，只需改这一层。
 */
@Component
@RequiredArgsConstructor
public class EntryStore {

    private final EntrySessionMapper sessionMapper;
    private final EntryMessageMapper messageMapper;
    private final ObjectMapper objectMapper;

    public EntrySessionView create(Long templateId, int templateVersion, String actor) {
        EntrySession session = new EntrySession();
        session.setTemplateId(templateId);
        session.setTemplateVersion(templateVersion);
        session.setState("CHATTING");
        session.setFollowupCount(0);
        session.setCreatedBy(actor);
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return load(session.getId());
    }

    public EntrySessionView load(Long id) {
        EntrySession session = sessionMapper.selectById(id);
        if (session == null) {
            throw BizException.notFound("录入会话 ");
        }
        EntrySessionView view = new EntrySessionView();
        view.setId(session.getId());
        view.setTemplateId(session.getTemplateId());
        view.setTemplateVersion(session.getTemplateVersion());
        view.setState(session.getState());
        view.setFollowupCount(session.getFollowupCount() == null ? 0 : session.getFollowupCount());
        view.setDraftSummary(session.getDraftSummary());
        view.setRecordId(session.getRecordId());
        view.setCreatedBy(session.getCreatedBy());
        view.setValues(readMap(session.getCollectedValues()));
        view.setBasis(readBasis(session.getCollectedBasis()));
        return view;
    }

    public void save(EntrySessionView view) {
        EntrySession session = sessionMapper.selectById(view.getId());
        if (session == null) {
            throw BizException.notFound("录入会话 ");
        }
        session.setState(view.getState());
        session.setFollowupCount(view.getFollowupCount());
        session.setCollectedValues(write(view.getValues()));
        session.setCollectedBasis(write(view.getBasis()));
        session.setDraftSummary(view.getDraftSummary());
        session.setRecordId(view.getRecordId());
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    public void appendMessage(Long sessionId, String role, String content) {
        EntryMessage message = new EntryMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
    }

    public List<EntryMessage> history(Long sessionId) {
        return messageMapper.selectList(Wrappers.<EntryMessage>lambdaQuery()
                .eq(EntryMessage::getSessionId, sessionId)
                .orderByAsc(EntryMessage::getId));
    }

    /** 只把采购员说的话拼成对话文本：追问是我们写的，不参与抽取 */
    public String dialogueOf(Long sessionId) {
        StringBuilder sb = new StringBuilder();
        for (EntryMessage m : history(sessionId)) {
            if (!"user".equals(m.getRole())) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(m.getContent());
        }
        return sb.toString();
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
            return new LinkedHashMap<>();
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
            return new LinkedHashMap<>();
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(500, "会话序列化失败: " + e.getMessage());
        }
    }
}