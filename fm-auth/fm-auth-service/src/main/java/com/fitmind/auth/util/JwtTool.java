package com.fitmind.auth.util;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTValidator;
import cn.hutool.jwt.signers.JWTSigner;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.fitmind.auth.common.constants.JwtConstants;
import com.fitmind.common.domain.dto.LoginUserDTO;
import com.fitmind.common.exceptions.BadRequestException;
import com.fitmind.common.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.util.Date;

/**
 * JWT 工具。
 *
 * <p>负责 token 的签发与解析：</p>
 * <ul>
 *     <li><b>accessToken</b>：短期凭证，前端放在 {@code Authorization} 头里，每次请求携带；</li>
 *     <li><b>refreshToken</b>：长期凭证，放在 HttpOnly Cookie 里，用于换取新的 accessToken。</li>
 * </ul>
 *
 * <p><b>安全设计</b>：每签发的 token 都会记录一个 {@code jti}（token 唯一 id）到 Redis。
 * 退出登录时删除 jti，即可让已签发的 token 立即失效——弥补了 JWT「签发后无法撤销」的短板。
 * 两类 token 的 jti 分别存放在不同 key 下，互不覆盖。</p>
 */
@Slf4j
@Component
public class JwtTool {

    /** 私钥签名器：签发 token */
    private final JWTSigner signer;

    /** 公钥验签器：校验 token */
    private final JWTSigner verifySigner;

    private final StringRedisTemplate redisTemplate;

    public JwtTool(KeyPair keyPair, StringRedisTemplate redisTemplate) {
        this.signer = JWTSignerUtil.createSigner(JwtConstants.JWT_ALGORITHM, keyPair.getPrivate());
        this.verifySigner = JWTSignerUtil.createSigner(JwtConstants.JWT_ALGORITHM, keyPair.getPublic());
        this.redisTemplate = redisTemplate;
    }

    /**
     * 签发 accessToken
     */
    public String createToken(LoginUserDTO user) {
        return buildToken(user,
                JwtConstants.JWT_TOKEN_TTL.toMillis(),
                JwtConstants.JWT_REDIS_KEY_PREFIX,
                JwtConstants.JWT_TOKEN_TTL);
    }

    /**
     * 签发 refreshToken
     */
    public String createRefreshToken(LoginUserDTO user) {
        return buildToken(user,
                JwtConstants.JWT_REFRESH_TTL.toMillis(),
                JwtConstants.JWT_REFRESH_REDIS_KEY_PREFIX,
                JwtConstants.JWT_REFRESH_TTL);
    }

    private String buildToken(LoginUserDTO user, long ttlMillis, String redisKeyPrefix,
                              java.time.Duration redisTtl) {
        long now = System.currentTimeMillis();
        String jti = IdUtil.fastSimpleUUID();
        // 记录 jti，退出登录时删除即可让 token 立即失效
        redisTemplate.opsForValue().set(
                redisKeyPrefix + user.getUserId(), jti, redisTtl);
        return JWT.create()
                .setPayload(JwtConstants.PAYLOAD_USER_KEY, JSONUtil.parseObj(user))
                .setJWTId(jti)
                .setIssuedAt(new Date(now))
                .setExpiresAt(new Date(now + ttlMillis))
                .setSigner(signer)
                .sign();
    }

    /**
     * 解析并校验 accessToken
     */
    public LoginUserDTO parseToken(String token) {
        return doParse(token, JwtConstants.JWT_REDIS_KEY_PREFIX);
    }

    /**
     * 解析并校验 refreshToken
     */
    public LoginUserDTO parseRefreshToken(String token) {
        return doParse(token, JwtConstants.JWT_REFRESH_REDIS_KEY_PREFIX);
    }

    private LoginUserDTO doParse(String token, String redisKeyPrefix) {
        JWT jwt;
        try {
            jwt = JWT.of(token).setSigner(verifySigner);
        } catch (Exception e) {
            throw new BadRequestException("登录信息无效");
        }
        try {
            JWTValidator.of(jwt).validateDate();
        } catch (Exception e) {
            throw new BadRequestException("登录已过期，请重新登录");
        }
        if (!jwt.verify()) {
            throw new BadRequestException("登录信息无效");
        }
        Object payload = jwt.getPayload(JwtConstants.PAYLOAD_USER_KEY);
        if (payload == null) {
            throw new BadRequestException("登录信息无效");
        }
        LoginUserDTO user = JSONUtil.toBean(JSONUtil.parseObj(payload), LoginUserDTO.class);

        // 校验 jti：退出登录后 jti 被删除，此处即校验失败
        Object jtiPayload = jwt.getPayload(JwtConstants.PAYLOAD_JTI_KEY);
        String jti = jtiPayload == null ? null : jtiPayload.toString();
        String cachedJti = redisTemplate.opsForValue().get(redisKeyPrefix + user.getUserId());
        if (cachedJti == null || !cachedJti.equals(jti)) {
            throw new BadRequestException("登录已失效，请重新登录");
        }
        return user;
    }

    /**
     * 退出登录：清理当前用户两类 token 的 jti
     */
    public void cleanJtiCache() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            log.warn("退出登录时未获取到当前用户，跳过 jti 清理");
            return;
        }
        redisTemplate.delete(JwtConstants.JWT_REDIS_KEY_PREFIX + userId);
        redisTemplate.delete(JwtConstants.JWT_REFRESH_REDIS_KEY_PREFIX + userId);
        log.info("用户 {} 退出登录，已清理 jti", userId);
    }
}
