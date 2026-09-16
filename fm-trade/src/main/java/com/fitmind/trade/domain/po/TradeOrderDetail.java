package com.fitmind.trade.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单明细（一单可挂多门课）。
 *
 * <p><b>★ 为什么课程名、封面、价格、有效期都要存快照</b>：
 * 教练随时可以改课程名、调价格、改有效期。如果订单只存 {@code course_id}，
 * 那"我三个月前花 199 买的课"在订单列表里会显示成"现在 299 的那门课"——
 * 对账时说不清，用户也会觉得系统有问题。订单是<b>交易凭证</b>，
 * 它必须记录"当时买的是什么、多少钱、多久有效"。</p>
 *
 * <p>{@code userId} 与 {@code status} 是从父单冗余下来的，目的很实在：
 * 查"我买过这门课吗"（下单前必查、加购物车也查）只用扫这一张表的一个索引，
 * 不用 join 回 {@code trade_order} 看父单状态。这属于<b>用一点冗余换掉一次 join</b>，
 * 在写少读多的判定场景里很划算。</p>
 */
@Data
@Accessors(chain = true)
@TableName("trade_order_detail")
public class TradeOrderDetail implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderId;

    /** 冗余的用户id（省一次 join） */
    private Long userId;

    private Long courseId;

    /** ★ 快照：下单时的课程名 */
    private String courseName;

    /** ★ 快照：下单时的封面 */
    private String coverUrl;

    /** ★ 快照：下单时课程单价（分） */
    private Integer price;

    /** 该明细分摊到的优惠（分） */
    private Integer discountAmount;

    /** 该明细实付（分） */
    private Integer realPayAmount;

    /** ★ 快照：下单时课程的有效期（天） */
    private Integer validDuration;

    /** 支付成功后算出的到期时间 */
    private LocalDateTime courseExpireTime;

    /** 明细状态，与父单同步冗余：1-待支付 2-已支付 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
