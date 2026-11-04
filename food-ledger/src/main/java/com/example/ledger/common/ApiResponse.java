package com.example.ledger.common;

import lombok.Data;

/**
 * 全站统一响应体：HTTP 200 + { code, message, data }。
 *
 * 为什么不用 HTTP 状态码表达业务错误：
 * 前端只需要认 body.code 一处，不必维护两套错误通道。
 * 401 / 404 / 409 这些业务码也走这里，前端读 err.code 即可分支。
 */
@Data
public class ApiResponse<T> {

    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setCode(0);
        r.setMessage("ok");
        r.setData(data);
        return r;
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }
}
