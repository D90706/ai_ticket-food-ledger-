package com.example.ticket.template;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 字段定义。结构必须与 mock/template.json 里的 fields 元素一致：
 * key / label / type / required / options / pattern
 *
 * required 用包装类型 Boolean，不用 boolean：
 * 为的是能区分「显式声明为选填」与「压根没填」。
 * 后者在保存模板时必须被拒绝——字段必须显式声明必填或选填。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FieldDef {

    private String key;
    private String label;
    private FieldType type;
    private Boolean required;

    /** 仅 enum 使用 */
    private List<String> options;

    /** 仅 text 使用，正则字符串 */
    private String pattern;

    /** 是否为必填（required 未声明时视为非法，这里容错为 false） */
    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }

    public boolean hasPattern() {
        return pattern != null && !pattern.isBlank();
    }
}
