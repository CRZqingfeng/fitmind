package com.fitmind.auth.service;

/**
 * 登录记录服务。
 */
public interface ILoginRecordService {

    /**
     * 记录一次成功登录
     *
     * @param cellPhone 手机号
     * @param userId    用户 id
     */
    void loginSuccess(String cellPhone, Long userId);

    /**
     * 记录一次登出，回填登出时间与登录时长
     *
     * @param userId 用户 id
     */
    void logout(Long userId);
}
