package com.example.ledger.common;

import lombok.Getter;

/**
 * 业务异常。
 *
 * code 就是最终返回给前端的那一个（默认 400）。
 * 几个约定俗成的用法：
 *   401 未登录
 *   404 找不到
 *   409 冲突（并发改、状态不允许）
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
