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
     * accessToken 有效期。
     *
     * <p>生产环境通常为 5~30 分钟，靠 refreshToken 续期；
     * 开发阶段为便于调试设为 30 天。</p>
     */
    public static final Duration JWT_TOKEN_TTL = Duration.ofMinutes(60 * 24 * 30);

    /** refreshToken 有效期 */
    public static final Duration JWT_REFRESH_TTL = Duration.ofMinutes(30);

    /** 勾选「记住我」时 refreshToken（Cookie）的有效期 */
    public static final Duration JWT_REMEMBER_ME_TTL = Duration.ofDays(7);
}
