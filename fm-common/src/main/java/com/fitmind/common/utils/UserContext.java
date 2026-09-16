package com.fitmind.common.utils;

/**
 * 用户上下文。
 *
 * <p>网关鉴权后会把用户 ID 写入请求头（默认 {@code user-info}），
 * 各微服务通过拦截器把该值放入本 ThreadLocal，业务代码即可随时读取当前登录用户，
 * 无需层层透传。</p>
 *
 * <p><b>注意</b>：必须在请求结束时调用 {@link #removeUser()} 清理，
 * 否则在 Tomcat 线程池复用场景下会造成用户信息串号。</p>
 */
public class UserContext {

    private static final ThreadLocal<Long> TL = new ThreadLocal<>();

    /**
     * 保存当前登录用户 ID
     */
    public static void setUser(Long userId) {
        TL.set(userId);
    }

    /**
     * 获取当前登录用户 ID，未登录返回 null
     */
    public static Long getUser() {
        return TL.get();
    }

    /**
     * 清理，防止线程复用导致的串号
     */
    public static void removeUser() {
        TL.remove();
    }
}
