package com.fitmind.common.domain;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 统一响应结果包装类。
 *
 * <p>所有 Controller 接口统一返回该结构，由 {@code WrapperResponseBodyAdvice} 自动包装，
 * 前端只需按 {@code code / msg / data} 解析。</p>
 *
 * @param <T> 业务数据类型
 */
@Data
@Accessors(chain = true)
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成功状态码 */
    public static final int SUCCESS_CODE = 200;
    /** 失败状态码 */
    public static final int ERROR_CODE = 500;

    /** 状态码：200 成功，其余为失败 */
    private int code;

    /** 提示信息 */
    private String msg;

    /** 业务数据 */
    private T data;

    /** 请求链路 id，便于排障时串联一次请求在各服务中的日志 */
    private String requestId;

    public R() {
    }

    public R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public R<T> requestId(String requestId) {
        this.requestId = requestId;
        return this;
    }

    // ==================== 成功 ====================

    public static <T> R<T> ok() {
        return new R<>(SUCCESS_CODE, "OK", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(SUCCESS_CODE, "OK", data);
    }

    public static <T> R<T> ok(T data, String msg) {
        return new R<>(SUCCESS_CODE, msg, data);
    }

    // ==================== 失败 ====================

    public static <T> R<T> error(String msg) {
        return new R<>(ERROR_CODE, msg, null);
    }

    public static <T> R<T> error(int code, String msg) {
        return new R<>(code, msg, null);
    }

    public boolean isSuccess() {
        return this.code == SUCCESS_CODE;
    }
}
