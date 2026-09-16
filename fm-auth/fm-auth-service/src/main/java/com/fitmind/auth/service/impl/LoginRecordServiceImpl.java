package com.fitmind.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.auth.domain.po.LoginRecord;
import com.fitmind.auth.mapper.LoginRecordMapper;
import com.fitmind.auth.service.ILoginRecordService;
import com.fitmind.common.utils.WebUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 登录记录服务实现。
 */
@Slf4j
@Service
public class LoginRecordServiceImpl extends ServiceImpl<LoginRecordMapper, LoginRecord>
        implements ILoginRecordService {

    @Override
    public void loginSuccess(String cellPhone, Long userId) {
        LoginRecord record = new LoginRecord()
                .setUserId(userId)
                .setCellPhone(cellPhone)
                .setLoginTime(LocalDateTime.now())
                .setLoginDate(LocalDate.now())
                .setIpv4(WebUtils.getClientIp());
        save(record);
        log.info("记录登录成功：userId={}，phone={}", userId, cellPhone);
    }

    @Override
    public void logout(Long userId) {
        // 找到该用户最近一次「未登出」的记录，回填登出时间与时长
        LoginRecord last = getOne(new LambdaQueryWrapper<LoginRecord>()
                .eq(LoginRecord::getUserId, userId)
                .isNull(LoginRecord::getLogoutTime)
                .orderByDesc(LoginRecord::getLoginTime)
                .last("limit 1"));
        if (last == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        long duration = last.getLoginTime() == null
                ? 0L : Duration.between(last.getLoginTime(), now).getSeconds();
        updateById(new LoginRecord()
                .setId(last.getId())
                .setLogoutTime(now)
                .setDuration(duration));
        log.info("记录登出：userId={}，本次在线 {} 秒", userId, duration);
    }
}
