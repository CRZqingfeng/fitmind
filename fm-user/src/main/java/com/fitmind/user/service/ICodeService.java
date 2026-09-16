package com.fitmind.user.service;

/**
 * 验证码服务。
 */
public interface ICodeService {

    /**
     * 发送验证码
     *
     * @param phone 手机号
     * @return 本次生成的验证码（开发模式下便于调试；生产环境不应返回给前端）
     */
    String sendVerifyCode(String phone);

    /**
     * 校验验证码
     *
     * @param phone 手机号
     * @param code  用户提交的验证码
     */
    void verifyCode(String phone, String code);

    /**
     * 是否为 Mock 短信模式（本地开发为 true）。
     *
     * <p>Mock 模式下允许把验证码回传给前端，方便调试；生产环境必须为 false。</p>
     */
    boolean isSmsMock();
}
