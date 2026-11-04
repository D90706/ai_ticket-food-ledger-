package com.example.ticket.common;

import lombok.Getter;

/**
 * 业务异常。code 用于前端区分错误类型，默认 400。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public static BizException notFound(String what) {
        return new BizException(404, what + "不存在");
    }
}
