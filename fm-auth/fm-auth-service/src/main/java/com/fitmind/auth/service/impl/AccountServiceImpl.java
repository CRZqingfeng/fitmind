package com.fitmind.auth.service.impl;

import com.fitmind.api.client.user.UserClient;
import com.fitmind.api.dto.user.LoginFormDTO;
import com.fitmind.auth.common.constants.AuthConstants;
import com.fitmind.auth.common.constants.AuthErrorInfo;
import com.fitmind.auth.common.constants.JwtConstants;
import com.fitmind.auth.service.IAccountService;
import com.fitmind.auth.service.ILoginRecordService;
import com.fitmind.auth.util.JwtTool;
import com.fitmind.common.domain.dto.LoginUserDTO;
import com.fitmind.common.exceptions.BadRequestException;
import com.fitmind.common.exceptions.UnauthorizedException;
import com.fitmind.common.utils.UserContext;
import com.fitmind.common.utils.WebUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 账户服务实现。
 *
 * <p>登录流程（手机号 + 验证码）：</p>
 * <ol>
 *     <li>通过 Feign 调 fm-user，由它完成「验证码校验 + 用户查询 + 状态校验」；</li>
 *     <li>拿到 {@link LoginUserDTO} 后签发 accessToken 与 refreshToken；</li>
 *     <li>refreshToken 写入 HttpOnly Cookie（前端 JS 读不到，防 XSS 窃取）；</li>
 *     <li>记录登录流水。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements IAccountService {

    private final JwtTool jwtTool;
    private final UserClient userClient;
    private final ILoginRecordService loginRecordService;

    @Override
    public String login(LoginFormDTO loginDTO, boolean isStaff) {
        // 1.委托用户服务校验登录信息（验证码/密码校验、用户状态校验都在那边）
        LoginUserDTO detail = userClient.queryUserDetail(loginDTO, isStaff);
        if (detail == null) {
            throw new UnauthorizedException("登录信息有误");
        }
        // 2.签发 token
        detail.setRememberMe(loginDTO.getRememberMe());
        String token = generateToken(detail);
        // 3.记录登录流水
        loginRecordService.loginSuccess(loginDTO.getCellPhone(), detail.getUserId());
        return token;
    }

    private String generateToken(LoginUserDTO detail) {
        // 2.1 签发 accessToken
        String token = jwtTool.createToken(detail);
        // 2.2 签发 refreshToken
        String refreshToken = jwtTool.createRefreshToken(detail);
        // 2.3 refreshToken 写入 HttpOnly Cookie
        int maxAge = Boolean.TRUE.equals(detail.getRememberMe())
                ? (int) JwtConstants.JWT_REMEMBER_ME_TTL.toSeconds()
                : -1;
        WebUtils.writeCookie(AuthConstants.REFRESH_HEADER, refreshToken, maxAge, true);
        return token;
    }

    @Override
    public void logout() {
        Long userId = UserContext.getUser();
        // 1.清理 jti，使已签发的 token 立即失效
        jwtTool.cleanJtiCache();
        // 2.回填登出流水
        if (userId != null) {
            loginRecordService.logout(userId);
        }
        // 3.清除 Cookie
        WebUtils.writeCookie(AuthConstants.REFRESH_HEADER, "", 0, true);
    }

    @Override
    public String refreshToken(String refreshToken) {
        // 1.校验 refreshToken
        LoginUserDTO user = jwtTool.parseRefreshToken(refreshToken);
        if (user == null) {
            throw new BadRequestException(AuthErrorInfo.Msg.INVALID_TOKEN);
        }
        // 2.签发新的 token
        return generateToken(user);
    }
}
