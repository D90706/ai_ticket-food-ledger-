package com.example.ticket.chat;

import com.example.ticket.common.BizException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用的内存会话存储。
 *
 * 编写它的过程本身就验证了 SessionStore 这层抽象是有价值的：
 * 编排逻辑可以完全脱离 MySQL 与 Spring 上下文被测试。
 */
public class InMemorySessionStore implements SessionStore {

    private final Map<Long, SessionView> sessions = new LinkedHashMap<>();
    private final Map<Long, List<ChatMessage>> messages = new LinkedHashMap<>();
    private final AtomicLong sessionSeq = new AtomicLong(0);
    private final AtomicLong messageSeq = new AtomicLong(0);

    @Override
    public SessionView create(Long templateId, int templateVersion) {
        SessionView view = new SessionView();
        view.setId(sessionSeq.incrementAndGet());
        view.setTemplateId(templateId);
        view.setTemplateVersion(templateVersion);
        sessions.put(view.getId(), view);
        messages.put(view.getId(), new ArrayList<>());
        return view;
    }

    @Override
    public SessionView load(Long sessionId) {
        SessionView view = sessions.get(sessionId);
        if (view == null) {
            throw BizException.notFound("会话 " + sessionId + " ");
        }
        return view;
    }

    @Override
    public void save(SessionView view) {
        sessions.put(view.getId(), view);
    }

    @Override
    public void appendMessage(Long sessionId, String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setId(messageSeq.incrementAndGet());
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        messages.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(m);
    }

    @Override
    public List<ChatMessage> history(Long sessionId) {
        return List.copyOf(messages.getOrDefault(sessionId, List.of()));
    }

    public List<ChatMessage> messagesOf(Long sessionId) {
        return history(sessionId);
    }
}
