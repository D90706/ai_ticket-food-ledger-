package com.example.ticket.preview.dto;

import com.example.ticket.quality.QualityReport;
import lombok.Data;

import java.util.Map;

/**
 * 试抽结果。字段与 mock/preview-response.json 的 data 一致。
 *
 * 它把「抽取层看到了什么」和「核验层怎么判」一起返回，
 * 让模板作者在编辑字段时立刻看到两件事：
 *   我设计的字段在这段对话上抽出来是什么样
 *   这张（想象中的）工单会是什么颜色、缺什么
 */
@Data
public class PreviewResponse {

    private String dialogue;
    private Map<String, Object> values;
    private Map<String, String> basis;
    private String suggestion;
    private String summary;
    private QualityReport quality;
}
