package com.fitmind.trade.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 退款申请。
 *
 * <p><b>★ 它是"退款过程"的唯一真相来源</b>：用户什么时候申请的、
 * 谁审的、同意还是拒绝、渠道单号是什么、最后成没成，全在这一张表里。
 * {@code trade_order_detail.status} 只是给订单列表看的<b>冗余摘要</b>，
 * 由退款流程统一写入。</p>
 *
 * <p><b>为什么按明细退款（挂着 orderDetailId 而不是 orderId）</b>：
 * 一笔订单可以买多门课，用户可能只想退其中一门。
 * 金额也是按明细分摊的（{@code real_pay_amount}），
 * 所以"退多少钱"只有挂在明细上才算得清。</p>
 *
 * <p><b>⚠️ 数据库里还有一个 {@code active_flag} 生成列</b>，本类<b>故意不映射</b>它：
 * 它是"进行中 = 明细id，否则 NULL"的计算列，只服务于
 * {@code uk_active_detail} 唯一索引（保证同一明细同时只有一笔进行中的申请）。
 * 生成列不能手工写入，映射进来反而容易写出错误的 set。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("refund_apply")
public class RefundApply implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键。
     *
     * <p>★ 它同时是<b>支付侧的 {@code biz_refund_order_no}</b> ——
     * 交易侧与支付侧靠这个号对上话。用自增而不是雪花：
     * 退款申请不需要"前端预生成 id"那种玩法（下单需要，因为要防重复提交），
     * 这里的防重交给唯一索引更直接。</p>
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 退的是哪个订单明细（一门课一次退款） */
    private Long orderDetailId;

    private Long orderId;

    /** 订单所属用户（★ 不是申请人 —— 管理员代申请时两者不同，退款打给谁看这个字段） */
    private Long userId;

    /** 退款金额（分）—— 服务端从明细实付取，前端传值无效 */
    private Integer refundAmount;

    /** 状态：见 {@link com.fitmind.trade.constants.RefundStatus} */
    private Integer status;

    /** 状态描述（给用户看的一句话） */
    private String message;

    /** 退款原因（分类，前端下拉选） */
    private String refundReason;

    /** 问题说明（用户填的补充） */
    private String questionDesc;

    /** 审批人id */
    private Long approver;

    /** 审批意见 */
    private String approveOpinion;

    private LocalDateTime approveTime;

    /** 支付侧退款单号（回调后回填，对账用） */
    private String refundOrderNo;

    /** 退款渠道标识 */
    private String refundChannel;

    /** 退款失败原因 */
    private String failedReason;

    /** 退款完成时间（成功或失败） */
    private LocalDateTime finishTime;

    /** 申请人id（学员本人 or 管理员） */
    private Long creater;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
