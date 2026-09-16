package com.fitmind.common.exceptions;

/**
 * 未认证异常（HTTP 401）。
 *
 * <p>网关校验 token 失败或未登录时抛出，返回给前端后由前端跳转登录页。</p>
 */
public class UnauthorizedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误状态码 */
    private final int code;

    public UnauthorizedException(String message) {
        this(401, message);
    }

    public UnauthorizedException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
