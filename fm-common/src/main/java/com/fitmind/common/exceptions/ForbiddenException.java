package com.fitmind.common.exceptions;

/**
 * 无权限异常（HTTP 403）。
 *
 * <p>已登录但权限不足时抛出。</p>
 */
public class ForbiddenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ForbiddenException(String message) {
        super(message);
    }
}
