package com.fitmind.auth.gateway.util;

import cn.hutool.core.exceptions.ValidateException;
import cn.hutool.json.JSONObject;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTValidator;
import com.fitmind.auth.common.constants.AuthConstants;
import com.fitmind.common.domain.R;
import com.fitmind.common.domain.dto.LoginUserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import static com.fitmind.auth.common.constants.AuthErrorInfo.Code.EXPIRED_TOKEN_CODE;
import static com.fitmind.auth.common.constants.AuthErrorInfo.Code.INVALID_TOKEN_CODE;
import static com.fitmind.auth.common.constants.AuthErrorInfo.Msg.*;
import static com.fitmind.auth.common.constants.JwtConstants.PAYLOAD_USER_KEY;

/**
 * 网关认证工具。
 *
 * <p>职责：用公钥验签并解析 token，得到 {@link LoginUserDTO}。</p>
 *
 * <p><b>M1 说明</b>：常见实现这里还包含基于 Redis 的 RBAC 权限点校验
 * （路径 → 所需角色）。M1 只做「登录态校验」，RBAC 属于管理端能力，
 * 留到管理端里程碑实现。</p>
 */
@Slf4j
public class AuthUtil {

    private final JwtSignerHolder jwtSignerHolder;

    public AuthUtil(JwtSignerHolder jwtSignerHolder) {
        this.jwtSignerHolder = jwtSignerHolder;
    }

    /**
     * 解析并校验 token。
     *
     * @param token 原始 token（可带 Bearer 前缀）
     * @return 成功时 data 为登录用户信息；失败时 code/msg 表明原因
     */
    public R<LoginUserDTO> parseToken(String token) {
        // 1.非空校验
        if (!StringUtils.hasText(token)) {
            return R.error(INVALID_TOKEN_CODE, INVALID_TOKEN);
        }
        // 2.去掉 Bearer 前缀
        if (token.startsWith(AuthConstants.TOKEN_PREFIX)) {
            token = token.substring(AuthConstants.TOKEN_PREFIX.length());
        }
        // 3.公钥就绪校验
        if (jwtSignerHolder.getJwtSigner() == null) {
            log.warn("公钥尚未加载完成，暂时无法验签");
            return R.error(INVALID_TOKEN_CODE, INVALID_TOKEN);
        }
        // 4.构建并验签
        JWT jwt;
        try {
            jwt = JWT.of(token).setSigner(jwtSignerHolder.getJwtSigner());
        } catch (Exception e) {
            log.warn("token 解析失败：{}", e.getMessage());
            return R.error(INVALID_TOKEN_CODE, INVALID_TOKEN);
        }
        if (!jwt.verify()) {
            return R.error(INVALID_TOKEN_CODE, INVALID_TOKEN);
        }
        // 5.有效期校验
        try {
            JWTValidator.of(jwt).validateDate();
        } catch (ValidateException e) {
            return R.error(EXPIRED_TOKEN_CODE, EXPIRED_TOKEN);
        }
        // 6.载荷格式校验
        Object userPayload = jwt.getPayload(PAYLOAD_USER_KEY);
        if (userPayload == null) {
            return R.error(INVALID_TOKEN_CODE, INVALID_TOKEN_PAYLOAD);
        }
        // 7.载荷转对象
        try {
            LoginUserDTO userDTO = ((JSONObject) userPayload).toBean(LoginUserDTO.class);
            return R.ok(userDTO);
        } catch (RuntimeException e) {
            return R.error(INVALID_TOKEN_CODE, INVALID_TOKEN_PAYLOAD);
        }
    }
}
