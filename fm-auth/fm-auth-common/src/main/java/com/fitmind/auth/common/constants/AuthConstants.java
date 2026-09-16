package com.fitmind.auth.common.constants;

/**
 * 认证相关常量（请求头等）。
 *
 * <p>认证链路中请求头的流转：</p>
 * <pre>
 * 前端 ──Authorization: token──▶ 网关
 * 网关 ──user-info: userId──▶ 资源服务   （UserInfoInterceptor 读取后放入 UserContext）
 * 网关 ──token-info: token──▶ 资源服务   （Feign 继续透传时使用）
 * </pre>
 */
public class AuthConstants {

    private AuthConstants() {
    }

    /** JWT 令牌请求头：前端携带 */
    public static final String AUTHORIZATION_HEADER = "authorization";

    /** 用户 id 请求头：网关写入，下游服务读取 */
    public static final String USER_HEADER = "user-info";

    /** 原始 token 请求头：网关写入，供 Feign 继续透传 */
    public static final String TOKEN_HEADER = "token-info";

    /** refreshToken 的 Cookie 名称（学员端） */
    public static final String REFRESH_HEADER = "refresh";

    /** refreshToken 的 Cookie 名称（管理端） */
    public static final String ADMIN_REFRESH_HEADER = "admin-refresh";

    /** 请求链路 id 请求头 */
    public static final String REQUEST_ID_HEADER = "requestId";

    /** JWT 令牌前缀 */
    public static final String TOKEN_PREFIX = "Bearer ";
}
