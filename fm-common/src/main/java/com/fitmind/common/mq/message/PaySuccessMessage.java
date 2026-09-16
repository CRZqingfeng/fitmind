package com.fitmind.common.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 支付成功通知（fm-pay 生产 → fm-trade 消费）。
 *
 * <p>放在 fm-common 的理由与 {@link PointsEventMessage} 一样：
 * 生产方与消费方是<b>不同的服务</b>，消息体只能待在双方都依赖的公共模块，
 * 否则消费方（或生产方）就要反向依赖对方的实现包。</p>
 *
 * <p><b>幂等不在这条消息里做</b>：第三方回调可能重复投递，pay 侧先用
 * 支付单的条件更新挡一道（只有"待支付 → 已支付"成功才发消息），
 * 消费侧再用订单的条件更新挡第二道。两道都是
 * {@code WHERE status = 预期旧状态}，重复投递自然变成 0 行更新。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
public class PaySuccessMessage {

    /** 支付单号 */
    private String payOrderNo;

    /** 业务订单号（trade_order.id） */
    private String bizOrderNo;

    /** 下单用户 */
    private Long bizUserId;

    /** 实付金额（分） */
    private Integer amount;

    /** 支付渠道码 */
    private String payChannelCode;

    private LocalDateTime paySuccessTime;
}
