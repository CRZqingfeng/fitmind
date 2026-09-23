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
        String token = generateToken(detail, isStaff);
        // 3.记录登录流水
        loginRecordService.loginSuccess(loginDTO.getCellPhone(), detail.getUserId());
        return token;
    }

    private String generateToken(LoginUserDTO detail, boolean isStaff) {
        // 2.1 签发 accessToken
        String token = jwtTool.createToken(detail);
        // 2.2 签发 refreshToken
        String refreshToken = jwtTool.createRefreshToken(detail);
        // 2.3 refreshToken 写入 HttpOnly Cookie
        //     ★ 按端写**不同的 Cookie 名** —— 见 refreshCookieName 的说明。
        //       （刷新时也会走到这里，所以 isStaff 必须一路传下来）
        int maxAge = Boolean.TRUE.equals(detail.getRememberMe())
                ? (int) JwtConstants.JWT_REMEMBER_ME_TTL.toSeconds()
                : -1;
        WebUtils.writeCookie(refreshCookieName(isStaff), refreshToken, maxAge, true);
        return token;
    }

    /**
     * 学员端与管理端各自的 refreshToken Cookie 名。
     *
     * <p>★ 为什么必须分开：<b>Cookie 的隔离维度里没有端口</b> ——
     * 它按 domain 隔离（协议 + 域名），而 {@code localStorage} 按 origin 隔离（含端口）。
     * 于是 {@code localhost:5173}(portal) 与 {@code localhost:5174}(admin)
     * 的 localStorage 互不相干，<b>Cookie 却是同一份</b>。
     * 若两端共用一个名字，后登录的一端会覆盖先登录的一端的续期凭证 ——
     * 症状是：先在 portal 登录、再去 admin 登录，回到 portal 后 access 一过期，
     * 续期读到的是<b>另一端</b>的 refreshToken，于是身份串号或直接掉登录。</p>
     */
    private static String refreshCookieName(boolean isStaff) {
        return isStaff ? AuthConstants.ADMIN_REFRESH_HEADER : AuthConstants.REFRESH_HEADER;
    }

    @Override
    public void logout(boolean isStaff) {
        Long userId = UserContext.getUser();
        // 1.清理 jti，使已签发的 token 立即失效
        jwtTool.cleanJtiCache();
        // 2.回填登出流水
        if (userId != null) {
            loginRecordService.logout(userId);
        }
        // 3.清除 Cookie
        //   ★ 只清**本端**的 —— 两端可以同时登录，
        //     登出学员端不该顺手把管理端的会话也清掉（那就又变回互相干扰了）
        WebUtils.writeCookie(refreshCookieName(isStaff), "", 0, true);
    }

    @Override
    public String refreshToken(String refreshToken, boolean isStaff) {
        // 1.校验 refreshToken
        LoginUserDTO user = jwtTool.parseRefreshToken(refreshToken);
        if (user == null) {
            throw new BadRequestException(AuthErrorInfo.Msg.INVALID_TOKEN);
        }
        // 2.签发新的 token（★ 续期也会重写 Cookie，所以要按端传 isStaff）
        return generateToken(user, isStaff);
    }
}
