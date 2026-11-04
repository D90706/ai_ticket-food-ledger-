package com.example.ticket.chat;

import java.util.List;

/**
 * 会话存储。
 *
 * 做成接口的理由：编排层要做的是分支判断，
 * 它不该知道会话值怎么序列化成 JSON、怎么落表。
 * 有了这层抽象，编排逻辑可以用内存实现完整单测，
 * 不需要数据库、不需要 Spring 上下文。
 *
 * 同时它也是模块边界：只有实现类认识 MyBatis。
 */
public interface SessionStore {

    SessionView create(Long templateId, int templateVersion);

    /** 找不到时抛 404，而不是返回 null——调用方不该处理「会话不存在」这种分支 */
    SessionView load(Long sessionId);

    void save(SessionView view);

    void appendMessage(Long sessionId, String role, String content);

    List<ChatMessage> history(Long sessionId);
}
