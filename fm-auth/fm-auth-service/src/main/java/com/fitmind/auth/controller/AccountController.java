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
        accountService.logout();
    }

    @Operation(summary = "刷新 token")
    @GetMapping("/refresh")
    public String refreshToken(
            @CookieValue(value = AuthConstants.REFRESH_HEADER, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BadRequestException("登录超时，请重新登录");
        }
        return accountService.refreshToken(refreshToken);
    }
}
