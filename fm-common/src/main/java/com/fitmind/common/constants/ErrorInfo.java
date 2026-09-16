package com.fitmind.common.constants;

/**
 * 通用错误信息与错误码。
 */
public class ErrorInfo {

    private ErrorInfo() {
    }

    /** 通用错误提示 */
    public static class Msg {
        private Msg() {
        }

        public static final String INVALID_VERIFY_CODE = "验证码错误";
        public static final String VERIFY_CODE_EXPIRED = "验证码已过期，请重新获取";
        public static final String VERIFY_CODE_TOO_FREQUENT = "验证码发送过于频繁，请稍后再试";
        public static final String INVALID_PARAM = "请求参数有误";
        public static final String SYSTEM_ERROR = "系统繁忙，请稍后再试";
    }

    /** 通用错误码 */
    public static class Code {
        private Code() {
        }

        public static final int BAD_REQUEST = 400;
        public static final int UNAUTHORIZED = 401;
        public static final int FORBIDDEN = 403;
        public static final int NOT_FOUND = 404;
        public static final int SERVER_ERROR = 500;
    }
}
