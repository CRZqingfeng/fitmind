package com.fitmind.pay.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.mq.message.PaySuccessMessage;
import com.fitmind.common.utils.RabbitMqHelper;
import com.fitmind.pay.constants.PayConstants;
import com.fitmind.pay.constants.PayErrorInfo;
import com.fitmind.pay.domain.dto.ApplyPayDTO;
import com.fitmind.pay.domain.po.PayChannel;
import com.fitmind.pay.domain.po.PayOrder;
import com.fitmind.pay.domain.vo.PayResultVO;
import com.fitmind.pay.mapper.PayChannelMapper;
import com.fitmind.pay.mapper.PayOrderMapper;
import com.fitmind.pay.service.IPayOrderService;
import com.fitmind.pay.service.IPayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 支付单服务实现。
 */
@Slf4j
@Service
public class PayOrderServiceImpl extends ServiceImpl<PayOrderMapper, PayOrder> implements IPayOrderService {

    /**
     * ★ 按渠道码装填的渠道实现（Spring 会把同类型的 Bean 按<b>名字</b>装进 Map）。
     *
     * <p>key 就是 {@code @Service("mockPay")} 里的 "mockPay"，
     * 与 {@code pay_channel.channel_code} 一一对应。</p>
     */
    private final Map<String, IPayService> payChannels;

    private final PayChannelMapper payChannelMapper;
    private final RabbitMqHelper rabbitMqHelper;

    @Value("${pay.order-expire-minutes:30}")
    private int orderExpireMinutes;

    public PayOrderServiceImpl(Map<String, IPayService> payChannels,
                               PayChannelMapper payChannelMapper,
                               RabbitMqHelper rabbitMqHelper) {
        this.payChannels = payChannels;
        this.payChannelMapper = payChannelMapper;
        this.rabbitMqHelper = rabbitMqHelper;
    }

    // ==================== 申请支付 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PayResultVO applyPay(ApplyPayDTO dto) {
        // 1. 校验渠道（必须已注册且启用）
        PayChannel channel = payChannelMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PayChannel>()
                        .eq(PayChannel::getChannelCode, dto.getPayChannelCode()));
        if (channel == null) {
            throw new BizIllegalException(
                    String.format(PayErrorInfo.Msg.CHANNEL_NOT_SUPPORTED, dto.getPayChannelCode()));
        }
        if (channel.getEnabled() == null || channel.getEnabled() != 1) {
            throw new BizIllegalException(
                    String.format(PayErrorInfo.Msg.CHANNEL_DISABLED, channel.getName()));
        }
        IPayService payService = payChannels.get(dto.getPayChannelCode());
        if (payService == null) {
            // 表里配了、代码里没有 —— 属于配置与实现不一致，直接暴露而不是静默降级
            throw new BizIllegalException(
                    String.format(PayErrorInfo.Msg.CHANNEL_NOT_SUPPORTED, dto.getPayChannelCode()));
        }
        if (dto.getAmount() == null || dto.getAmount() <= 0) {
            throw new BizIllegalException(PayErrorInfo.Msg.PAY_ORDER_AMOUNT_ILLEGAL);
        }

        // 2. 复用：同一业务订单若已有待支付单，直接返回它（用户刷新页面不该产生第二笔）
        PayOrder pending = baseMapper.getPendingByBizOrderNo(
                dto.getBizOrderNo(), PayConstants.PAY_STATUS_NO_PAY);
        if (pending != null) {
            log.info("复用已有待支付单：payOrderNo={}, bizOrderNo={}",
                    pending.getPayOrderNo(), dto.getBizOrderNo());
            return toResult(pending, true);
        }

        // 3. 建单（支付单号：PAY + 雪花）
        LocalDateTime now = LocalDateTime.now();
        PayOrder po = new PayOrder()
                .setPayOrderNo(PayConstants.PAY_ORDER_NO_PREFIX + IdUtil.getSnowflakeNextIdStr())
                .setBizOrderNo(dto.getBizOrderNo())
                .setBizUserId(dto.getBizUserId())
                .setPayChannelCode(dto.getPayChannelCode())
                .setAmount(dto.getAmount())
                .setStatus(PayConstants.PAY_STATUS_NO_PAY)
                .setExpireTime(now.plusMinutes(orderExpireMinutes));

        // 4. 调渠道拿支付链接（Mock 渠道只是拼个本地地址；真实渠道这里会调第三方 SDK）
        payService.createPayOrder(po);

        save(po);
        log.info("创建支付单：payOrderNo={}, bizOrderNo={}, 渠道={}, 金额={}分, 过期={}",
                po.getPayOrderNo(), po.getBizOrderNo(), po.getPayChannelCode(),
                po.getAmount(), po.getExpireTime());
        return toResult(po, false);
    }

    // ==================== 支付成功（回调与 Mock 共用） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean handlePaySuccess(String payOrderNo) {
        PayOrder po = baseMapper.getByPayOrderNo(payOrderNo);
        if (po == null) {
            throw new BizIllegalException(PayErrorInfo.Msg.PAY_ORDER_NOT_EXISTS);
        }
        // 过期检查：过期的支付单不该被"补票"成功
        if (po.getExpireTime() != null && po.getExpireTime().isBefore(LocalDateTime.now())) {
            log.warn("支付单已过期，拒绝支付成功回调：payOrderNo={}, expire={}", payOrderNo, po.getExpireTime());
            throw new BizIllegalException(PayErrorInfo.Msg.PAY_ORDER_EXPIRED);
        }

        // ★★ 条件更新：只有"待支付 → 已支付"成功才继续。
        //    第三方回调可能重复投递，第二次及以后这里返回 0 → 不发 MQ。
        int n = baseMapper.changeStatus(payOrderNo,
                PayConstants.PAY_STATUS_NO_PAY, PayConstants.PAY_STATUS_PAYED);
        if (n == 0) {
            log.info("支付单已是终态，重复回调被忽略（幂等）：payOrderNo={}, 当前状态={}",
                    payOrderNo, po.getStatus());
            return false;
        }
        baseMapper.markPaySuccessTime(payOrderNo);

        // 通知交易模块。sendAfterCommit：事务没提交就发，消费端可能查不到这笔支付单
        rabbitMqHelper.sendAfterCommit(MqConstants.Exchange.PAY_EXCHANGE,
                MqConstants.Key.PAY_SUCCESS_KEY,
                PaySuccessMessage.of(payOrderNo, po.getBizOrderNo(), po.getBizUserId(),
                        po.getAmount(), po.getPayChannelCode(), LocalDateTime.now()));
        log.info("支付成功：payOrderNo={}, bizOrderNo={}, 已通知交易模块", payOrderNo, po.getBizOrderNo());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean closePayOrder(String payOrderNo) {
        int n = baseMapper.changeStatus(payOrderNo,
                PayConstants.PAY_STATUS_NO_PAY, PayConstants.PAY_STATUS_CLOSED);
        if (n == 0) {
            return false;
        }
        log.info("关闭支付单：payOrderNo={}", payOrderNo);
        return true;
    }

    @Override
    public PayOrder getByPayOrderNo(String payOrderNo) {
        return baseMapper.getByPayOrderNo(payOrderNo);
    }

    private PayResultVO toResult(PayOrder po, boolean reused) {
        return new PayResultVO()
                .setPayOrderNo(po.getPayOrderNo())
                .setQrCodeUrl(po.getQrCodeUrl())
                .setExpireTime(po.getExpireTime())
                .setReused(reused);
    }
}
