package com.fitmind.auth.service;

import com.fitmind.api.dto.user.LoginFormDTO;

/**
 * 账户服务。
 */
public interface IAccountService {

    /**
     * 登录并签发 token
     *
     * @param loginDTO 登录表单
     * @param isStaff  是否管理端登录
     * @return accessToken
     */
    String login(LoginFormDTO loginDTO, boolean isStaff);

    /**
     * 退出登录
     */
    void logout();

    /**
     * 用 refreshToken 换取新的 token
     *
     * @param refreshToken Cookie 中的 refreshToken
     * @return 新的 accessToken
     */
    String refreshToken(String refreshToken);
}
