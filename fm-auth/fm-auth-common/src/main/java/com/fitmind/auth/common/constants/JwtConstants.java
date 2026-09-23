package com.fitmind.auth.common.constants;

import java.time.Duration;

/**
 * JWT 相关常量。
 */
public class JwtConstants {

    private JwtConstants() {
    }

    /** JWT 载荷中存放用户信息的 key */
    public static final String PAYLOAD_USER_KEY = "user";

    /** JWT 载荷中存放 token 唯一 id 的 key */
    public static final String PAYLOAD_JTI_KEY = "jti";

    /** accessToken 的 JTI 在 Redis 中的 key 前缀 */
    public static final String JWT_REDIS_KEY_PREFIX = "jwt:uid:";

    /** refreshToken 的 JTI 在 Redis 中的 key 前缀（与 accessToken 分开，避免互相覆盖） */
    public static final String JWT_REFRESH_REDIS_KEY_PREFIX = "jwt:refresh:uid:";

    /** 签名算法：RSA + SHA256，非对称 */
    public static final String JWT_ALGORITHM = "rs256";

    /**
     * ★★ 历史（M8-1 修正）：这里原先是 {@code ofMinutes(60 * 24 * 30)}（**30 天**），
     *   而下面 refreshToken 只有 **30 分钟** —— 两个**配反了**。后果有两层：
     *     ① 安全：access 长期有效，令牌一旦泄漏，攻击窗口长达一个月；
     *     ② ★ 更要命：前端的续期是**「401 时被动触发」**，access 既然有 30 天，
     *        续期链路就**从来不会被执行到** —— 等于一直没被真正验证过。
     *   所以这次改小它不只是"把数字改对"，而是**第一次让续期成为常规路径**，
     *   验收必须**连续期一起验**（validate-m8-1.mjs 就是这么做的）。
     */
    public static final Duration JWT_TOKEN_TTL = Duration.ofMinutes(30);

    /** refreshToken 有效期 */
    public static final Duration JWT_REFRESH_TTL = Duration.ofDays(7);

    /** 勾选「记住我」时 refreshToken（Cookie）的有效期 */
    public static final Duration JWT_REMEMBER_ME_TTL = Duration.ofDays(7);
}
