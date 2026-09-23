package com.fitmind.auth.controller;

import com.fitmind.api.dto.user.LoginFormDTO;
import com.fitmind.auth.common.constants.AuthConstants;
import com.fitmind.auth.service.IAccountService;
import com.fitmind.common.exceptions.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 账户登录相关接口。
 *
 * <p>网关视角的访问路径：{@code /as/accounts/**}（网关会剥掉 /as 前缀）。</p>
 *
 * <p>★★ 学员端与管理端各有<b>一套对称的「登录 / 刷新 / 登出」</b>端点，
 * 两端的 refreshToken Cookie 名互不相同（{@code refresh} / {@code admin-refresh}）。
 * 原因是 <b>Cookie 的隔离维度里没有端口</b> —— 同 host 的不同端口会共享同一份 Cookie，
 * 若两端共用一个名字，后登录的一端会覆盖先登录的一端的续期凭证。
 * 详见 {@code AccountServiceImpl#refreshCookieName(boolean)}。</p>
 */
@RestController
@RequestMapping("/accounts")
@Tag(name = "账户管理")
@RequiredArgsConstructor
public class AccountController {

    private final IAccountService accountService;

    @Operation(summary = "登录并获取 token")
    @PostMapping("/login")
    public String login(@Valid @RequestBody LoginFormDTO loginFormDTO) {
        return accountService.login(loginFormDTO, false);
    }

    @Operation(summary = "管理端登录并获取 token")
    @PostMapping("/admin/login")
    public String adminLogin(@Valid @RequestBody LoginFormDTO loginFormDTO) {
        return accountService.login(loginFormDTO, true);
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public void logout() {
        accountService.logout(false);
    }

    @Operation(summary = "管理端退出登录")
    @PostMapping("/admin/logout")
    public void adminLogout() {
        accountService.logout(true);
    }

    @Operation(summary = "刷新 token")
    @GetMapping("/refresh")
    public String refreshToken(
            @CookieValue(value = AuthConstants.REFRESH_HEADER, required = false) String refreshToken) {
        return doRefresh(refreshToken, false);
    }

    @Operation(summary = "管理端刷新 token")
    @GetMapping("/admin/refresh")
    public String adminRefresh(
            @CookieValue(value = AuthConstants.ADMIN_REFRESH_HEADER, required = false) String refreshToken) {
        return doRefresh(refreshToken, true);
    }

    /**
     * 两个刷新端点的逻辑完全一致，差别只在「从哪个 Cookie 取 refreshToken」。
     *
     * @param refreshToken 对应端的 Cookie 值（可能不存在）
     * @param isStaff      是否管理端（决定续期后重写哪个 Cookie 名）
     */
    private String doRefresh(String refreshToken, boolean isStaff) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BadRequestException("登录超时，请重新登录");
        }
        return accountService.refreshToken(refreshToken, isStaff);
    }
}
