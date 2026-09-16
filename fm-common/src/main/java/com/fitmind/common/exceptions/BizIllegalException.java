package com.fitmind.common.exceptions;

/**
 * 业务异常。
 *
 * <p>用于业务规则不满足时中断流程并返回友好提示，例如「该训练计划已打卡」。
 * 由 {@code CommonExceptionAdvice} 统一捕获转换为 {@code R.error(...)}。</p>
 */
public class BizIllegalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BizIllegalException(String message) {
        this(500, message);
    }

    public BizIllegalException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
