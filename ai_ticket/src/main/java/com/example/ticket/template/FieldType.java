package com.example.ticket.template;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 字段类型。只支持 6 种，且不再扩张（设计上的非目标）。
 *
 * JSON 里用小写字符串，枚举名用大写，
 * @JsonValue / @JsonCreator 负责两边的映射。
 */
public enum FieldType {

    TEXT("text"),
    ENUM("enum"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    DATE("date"),
    IMAGE("image");

    private final String code;

    FieldType(String code) {
        this.code = code;
    }

    @JsonValue
    public String getCode() {
        return code;
    }

    @JsonCreator
    public static FieldType fromCode(String code) {
        if (code != null) {
            for (FieldType type : values()) {
                if (type.code.equalsIgnoreCase(code.trim())) {
                    return type;
                }
            }
        }
        throw new IllegalArgumentException("未知字段类型: " + code);
    }

    /** image 类型 AI 不处理，一律留空交人工补 */
    public boolean isAiUnreadable() {
        return this == IMAGE;
    }

    /** 只有 text 允许配 pattern，其它类型本身已由类型约束 */
    public boolean supportsPattern() {
        return this == TEXT;
    }
}
