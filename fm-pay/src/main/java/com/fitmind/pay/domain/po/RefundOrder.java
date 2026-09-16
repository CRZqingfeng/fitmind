package com.fitmind.pay.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 退款单（支付侧）。
 *
 * <p><b>★ 两个单号各有用处</b>：</p>
 * <ul>
 *   <li>{@code refundOrderNo} —— 本系统生成，<b>给渠道看</b>（渠道回执、对账都认这个号）</li>
 *   <li>{@code bizRefundOrderNo} —— 业务侧的退款申请 id（{@code fm_trade.refund_apply.id}），
 *       用来把渠道回执<b>翻译回业务语言</b></li>
 * </ul>
 *
 * <p>这与支付单的 {@code payOrderNo} / {@code bizOrderNo} 是同一套思路：
 * <b>渠道的号与业务的号分开</b>。混用一个号的问题是"一笔业务可以发起多次尝试"时
 * 就没法区分了 —— 支付失败重试、退款失败重申请，都会产生新的渠道单号。</p>
 *
 * <p><b>为什么存 {@code totalAmount}</b>：部分退款时渠道（微信）要求同时传
 * "原订单总额"和"本次退款额"，它靠这两个数校验一致性。全额退款时两者相等。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("refund_order")
public class RefundOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 退款单号（本系统生成，给渠道用） */
    private String refundOrderNo;

    /** ★ 业务退款单号（fm_trade.refund_apply.id），唯一索引在此列上 */
    private String bizRefundOrderNo;

    /** 业务订单号（trade_order.id） */
    private String bizOrderNo;

    /** 退款用户（冗余：回调时不用回查交易服务） */
    private Long bizUserId;

    /** 原支付单号 */
    private String payOrderNo;

    /** 支付渠道码（同时决定用哪个渠道实现退款） */
    private String payChannelCode;

    /** 退款金额（分） */
    private Integer refundAmount;

    /** 原支付总额（分）—— 部分退款时渠道需要 */
    private Integer totalAmount;

    /** 是否全额退款：0-否 1-是 */
    private Integer isSplit;

    /** 退款状态：1-退款中 2-退款成功 3-退款失败 */
    private Integer status;

    /** 渠道回执里的退款渠道标识 */
    private String refundChannel;

    /** 渠道返回码 */
    private String resultCode;

    /** 渠道返回描述 / 失败原因 */
    private String resultMsg;

    /** 退款完成时间（成功或失败） */
    private LocalDateTime finishTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 是否已退款成功（供内部判断，不映射数据库） */
    public boolean success() {
        return status != null && status == com.fitmind.pay.constants.RefundStatus.SUCCESS.getValue();
    }

    /** 是否退款失败 */
    public boolean failed() {
        return status != null && status == com.fitmind.pay.constants.RefundStatus.FAILED.getValue();
    }

    /** 是否还在退款中 */
    public boolean running() {
        return status != null && status == com.fitmind.pay.constants.RefundStatus.REFUNDING.getValue();
    }
}
