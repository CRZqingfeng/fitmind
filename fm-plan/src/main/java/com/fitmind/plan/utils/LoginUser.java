package com.fitmind.plan.utils;

import com.fitmind.common.utils.AssertUtils;
import com.fitmind.common.utils.UserContext;
import com.fitmind.plan.constants.PlanErrorInfo;

/**
 * 当前登录用户。
 *
 * <p><b>为什么要有这一层</b>：本模块每个接口都要用户 id，直接写
 * {@code UserContext.getUser()} 会拿到 null（网关没透传时），然后拿 null 去查库，
 * 得到的是"查不到任何数据"——接口 200、列表空，看起来像"没数据"而不是"没登录"。
 * 这里统一断言，null 就抛 400 + "登录信息已失效"。</p>
 *
 * <p>★ 禁止从 Header 里自己抠 userId：网关头名是常量 {@code AuthConstants.USER_HEADER}，
 * 各处硬编码字符串迟早对不上，而且绕过了 SDK 的拦截器就等于绕过了鉴权。</p>
 */
public final class LoginUser {

    private LoginUser() {
    }

    /** 当前用户 id，未登录直接抛业务异常 */
    public static Long id() {
        Long userId = UserContext.getUser();
        AssertUtils.isNotNull(userId, PlanErrorInfo.Msg.LOGIN_REQUIRED);
        return userId;
    }
}
