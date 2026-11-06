package com.fitmind.user.constants;

/**
 * 用户模块错误提示。
 */
public class UserErrorInfo {

    private UserErrorInfo() {
    }

    /** 错误提示 */
    public static class Msg {
        private Msg() {
        }

        public static final String USER_ID_NOT_EXISTS = "用户不存在";
        public static final String PHONE_NOT_EXISTS = "该手机号尚未注册";
        public static final String PHONE_ALREADY_EXISTS = "该手机号已注册";
        public static final String INVALID_UN = "用户名或手机号不能为空";
        public static final String INVALID_UN_OR_PW = "用户名或密码错误";
        public static final String INVALID_VERIFY_CODE = "验证码错误";
        public static final String VERIFY_CODE_TOO_FREQUENT = "验证码发送过于频繁，请稍后再试";
        public static final String USER_FROZEN = "账户已被禁用，请联系管理员";
        public static final String ILLEGAL_LOGIN_TYPE = "不支持的登录方式";
        public static final String NON_STUDENT = "该账号不是学员账号";
        public static final String NON_STAFF = "该账号不是管理端账号";
        public static final String PROFILE_NOT_EXISTS = "尚未填写身体档案";
        /**
         * ★ P0-7：连续失败达到上限后的提示。
         *
         * <p>刻意<b>不</b>回显"还剩几次"——那等于告诉撞库的人"再试 2 次就成"。
         * 也不说"账号已锁定"（会泄露账号是否存在），只说"登录失败次数过多"。</p>
         */
        public static final String LOGIN_TOO_MANY_ATTEMPTS = "登录失败次数过多，请 15 分钟后再试";
    }
}
