package com.example.ticket.retrieval;

import com.example.ticket.template.TemplateSchema;

import java.util.List;

/**
 * 知识检索。设计里的第二个可替换点。
 *
 * 当前实现是全量注入（几千字的领域知识直接塞进 prompt），
 * 因为在这个量级上召回率是 100%，而且少了一整层会出错的组件。
 *
 * 之所以仍然保留接口，是为了将来语料涨到十万字时，
 * 换成向量检索只改一个实现类，不动 prompt 拼装与业务逻辑。
 */
public interface Retriever {

    /**
     * @param query  本轮对话内容，供向量实现打分用；全量实现会忽略它
     * @param schema 提供领域知识原文
     * @return 需要注入 prompt 的知识片段
     */
    List<String> retrieve(String query, TemplateSchema schema);
}
