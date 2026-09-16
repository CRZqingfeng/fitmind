package com.fitmind.common.utils;

/**
 * 原始 token 上下文。
 *
 * <p>网关鉴权后会把原始 token 通过请求头 {@code token-info} 透传，
 * 资源服务的拦截器把它存进本 ThreadLocal。业务侧一般用不到，
 * 主要供「Feign 调用其他服务时继续透传 token」使用。</p>
 */
public class TokenContext {

    private static final ThreadLocal<String> TL = new ThreadLocal<>();

    private TokenContext() {
    }

    public static void setToken(String token) {
        TL.set(token);
    }

    public static String getToken() {
        return TL.get();
    }

    public static void removeToken() {
        TL.remove();
    }
}
