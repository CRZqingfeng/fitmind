package com.fitmind.auth.common.constants;

/**
 * 认证相关错误信息与错误码。
 */
public class AuthErrorInfo {

    private AuthErrorInfo() {
    }

    /** 错误码 */
    public static class Code {
        private Code() {
        }

        /** token 非法 */
        public static final int INVALID_TOKEN_CODE = 4011;
        /** token 已过期 */
        public static final int EXPIRED_TOKEN_CODE = 4012;
        /** 未登录 */
        public static final int NOT_LOGIN_CODE = 4013;
    }

    /** 错误提示 */
    public static class Msg {
        private Msg() {
        }

        public static final String INVALID_TOKEN = "登录信息无效，请重新登录";
        public static final String EXPIRED_TOKEN = "登录已过期，请重新登录";
        public static final String INVALID_TOKEN_PAYLOAD = "登录信息格式有误，请重新登录";
        public static final String NOT_LOGIN = "请先登录";
        public static final String FORBIDDEN = "没有访问权限";
        public static final String INVALID_UN_OR_PW = "用户名或密码错误";
        public static final String USER_FROZEN = "账户已被禁用，请联系管理员";
        public static final String PHONE_NOT_EXISTS = "该手机号尚未注册";
        public static final String PHONE_ALREADY_EXISTS = "该手机号已注册";
        public static final String ILLEGAL_LOGIN_TYPE = "不支持的登录方式";
    }
}
