package com.example.ticket.retrieval;

import com.example.ticket.template.TemplateSchema;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 全量注入：把模板的领域知识整段返回。
 *
 * 为什么不做检索：
 *   一个店铺的售后政策 + 尺码表通常几千字，
 *   全量塞进 prompt 的召回率是 100%，
 *   而检索会引入切分、embedding、召回失败三个新的出错点。
 *
 * 这个选择有一个前提：上下文要装得下。
 * 当前模型上下文 64K，几千字远未触及。
 * 一旦知识量涨到需要检索，换 VectorRetriever 即可。
 */
@Component
public class FullInjectRetriever implements Retriever {

    @Override
    public List<String> retrieve(String query, TemplateSchema schema) {
        String knowledge = schema.getKnowledge();
        if (knowledge == null || knowledge.isBlank()) {
            return List.of();
        }
        return List.of(knowledge);
    }
}
