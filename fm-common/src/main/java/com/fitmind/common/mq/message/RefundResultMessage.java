package com.fitmind.common.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 退款结果通知（fm-pay 生产 → fm-trade 消费）。
 *
 * <p><b>★ 为什么退款结果要"发回来"而不是同步等返回值</b>：
 * 真实渠道（微信退款）是<b>异步</b>的 —— 调它的退款接口只得到"已受理"，
 * 最终成不成要等它回调或者我们主动轮询。支付宝则可能当场返回成功。
 * 也就是说<b>渠道的返回语义本身就不统一</b>，交易侧不能依赖"调完就知道结果"。
 * 统一成"结果以回调/MQ 为准"，两种渠道就都能接。</p>
 *
 * <p>字段里的 {@code status} 用整数而不是枚举：跨服务的消息体不共享实现类，
 * 枚举一旦在两边版本不一致就会反序列化失败，而 {@code int} 不会有这个问题
 * （这与 {@link PaySuccessMessage} 保持一致）。</p>
 *
 * <p><b>幂等</b>：消费侧（fm-trade）用 {@code refund_apply} 的状态条件更新兜住 ——
 * 重复投递时第二次更新 0 行，不会重复改订单、不会重复撤销报名。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
public class RefundResultMessage {

    /** 退款中（渠道已受理，结果未知） */
    public static final int RUNNING = 1;
    /** 退款成功 */
    public static final int SUCCESS = 2;
    /** 退款失败 */
    public static final int FAILED = 3;

    /** ★ 业务退款单号（= fm-trade 的 refund_apply.id），交易侧靠它定位退款申请 */
    private String bizRefundOrderNo;
    /** 业务订单号（trade_order.id） */
    private String bizOrderNo;
    /** 支付渠道侧的退款单号（对账用） */
    private String refundOrderNo;
    /** 退款用户 */
    private Long userId;
    /** 退款金额（分） */
    private Integer refundAmount;
    /** 退款状态：见本类常量 RUNNING / SUCCESS / FAILED */
    private Integer status;
    /** 渠道标识（Mock 渠道会回填自己的码） */
    private String refundChannel;
    /** 失败原因（仅失败时有值） */
    private String failReason;
    private LocalDateTime finishTime;
}
