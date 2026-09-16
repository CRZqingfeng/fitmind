package com.fitmind.user.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.fitmind.user.constants.UserConstants;
import com.fitmind.user.constants.UserErrorInfo;
import com.fitmind.common.exceptions.BadRequestException;
import com.fitmind.user.service.ICodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;


/**
 * 验证码服务实现。
 *
 * <p>流程：<b>频率校验 → 生成验证码 → 存 Redis → 发送短信</b>。</p>
 *
 * <p><b>两个工程化细节</b>：</p>
 * <ul>
 *     <li><b>防止短信轰炸</b>：同一手机号 60 秒内只允许发送一次（Redis 做标记）；</li>
 *     <li><b>验证码复用</b>：若 Redis 中已有未过期的验证码，则复用而不是重新生成，
 *         避免用户连续点击导致前面收到的验证码立即失效。</li>
 * </ul>
 *
 * <p><b>M1 的短信为 Mock</b>：真正的短信服务（fm-message）尚未实现，
 * 这里把验证码打印到日志；当 {@code fm.sms.mock=true} 时还会随响应返回，
 * 方便本地调试。生产环境应关闭该开关。</p>
 */
@Slf4j
@Service
public class CodeServiceImpl implements ICodeService {

    private final StringRedisTemplate redisTemplate;

    /** 是否为 Mock 模式（本地开发用） */
    @Value("${fm.sms.mock:true}")
    private boolean smsMock;

    public CodeServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String sendVerifyCode(String phone) {
        // 1.频率校验：60 秒内只能发一次
        String limitKey = UserConstants.USER_VERIFY_CODE_LIMIT_KEY + phone;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(limitKey, "1", UserConstants.USER_VERIFY_CODE_INTERVAL);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BadRequestException(UserErrorInfo.Msg.VERIFY_CODE_TOO_FREQUENT);
        }

        // 2.取已有验证码，没有则生成
        String codeKey = UserConstants.USER_VERIFY_CODE_KEY + phone;
        String code = redisTemplate.opsForValue().get(codeKey);
        if (!StringUtils.hasText(code)) {
            code = RandomUtil.randomNumbers(UserConstants.VERIFY_CODE_LENGTH);
            redisTemplate.opsForValue().set(codeKey, code, UserConstants.USER_VERIFY_CODE_TTL);
        }

        // 3.发送短信（M1 为 Mock，仅打日志）
        sendSms(phone, code);
        return code;
    }

    @Override
    public void verifyCode(String phone, String code) {
        String codeKey = UserConstants.USER_VERIFY_CODE_KEY + phone;
        String cachedCode = redisTemplate.opsForValue().get(codeKey);
        if (!StringUtils.hasText(cachedCode) || !cachedCode.equals(code)) {
            throw new BadRequestException(UserErrorInfo.Msg.INVALID_VERIFY_CODE);
        }
        // 校验通过后立即删除，防止同一验证码被重复使用
        redisTemplate.delete(codeKey);
        redisTemplate.delete(UserConstants.USER_VERIFY_CODE_LIMIT_KEY + phone);
    }

    /**
     * 发送短信验证码。
     *
     * <p>当前为 Mock 实现：只打日志。后续接入 fm-message 服务后，
     * 这里改为通过 MQ 异步下发即可，调用方无需改动。</p>
     */
    private void sendSms(String phone, String code) {
        if (smsMock) {
            log.warn("【Mock 短信】向 {} 发送验证码：{}", phone, code);
        } else {
            log.info("向 {} 发送验证码（真实短信通道待接入 fm-message）", phone);
        }
    }

    /** 供 Controller 判断是否可以把验证码回传给前端（仅开发模式） */
    @Override
    public boolean isSmsMock() {
        return smsMock;
    }
}
