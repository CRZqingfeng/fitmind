package com.fitmind.promotion.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 优惠券（券模板）。
 *
 * <p><b>券模板 vs 用户券</b>：这里存的是"运营配置的规则"，
 * 用户领到手的是 {@code user_coupon} 里的一行。
 * 一张模板可以发出 N 张用户券 —— 这个拆分是必须的，
 * 否则"谁领了、用没用、什么时候过期"就没地方记。</p>
 *
 * <p>⚠️ 日期字段上<b>不要</b>加 {@code @JsonFormat(pattern = "...")}：
 * 那会把格式钉死成一种，反而让原本能用的 ISO 格式失效（本项目踩过）。
 * {@code LocalDateTime} 的"两种格式都接受"已由 fm-common 的
 * {@code JacksonConfig} 全局提供，字段上不必再标注。</p>
 */
@Data
@Accessors(chain = true)
@TableName("coupon")
public class Coupon implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private String name;

    /** 折扣类型：1-满减 2-每满减 3-折扣 4-无门槛 */
    private Integer discountType;

    /**
     * 是否限定作用范围：0-不限 1-限定。
     *
     * <p>⚠️ <b>必须加 {@code @TableField("`specific`")} 的反引号</b>：
     * {@code SPECIFIC} 是 MySQL 的保留字，而 MyBatis-Plus 生成 SQL 时
     * <b>不会自动给列名加反引号</b> —— 不加就会报
     * {@code You have an error in your SQL syntax ... near 'specific, discount_value...'}。</p>
     *
     * <p>这与 {@code trade_order} 表名要避开 {@code ORDER} 是同一类问题：
     * <b>MySQL 保留字在 MP 下必须自己加反引号</b>，建表时写了反引号也没用
     * （DDL 是我们手写的，DML 是 MP 生成的）。</p>
     */
    @TableField(value = "`specific`")
    private Integer specific;

    /** 折扣值：满减存金额(分)，折扣存折扣率(8折存80) */
    private Integer discountValue;

    /** 使用门槛（分），0 表示无门槛 */
    private Integer thresholdAmount;

    /** 最高优惠（分），0 表示不限制 */
    private Integer maxDiscountAmount;

    /** 获取方式：1-手动领取 2-兑换码 */
    private Integer obtainWay;

    /** 状态：1-待发放 2-发放中 3-已结束 */
    private Integer status;

    private Integer totalNum;

    private LocalDateTime issueBeginTime;

    private LocalDateTime issueEndTime;

    /** 每人限领数量 */
    private Integer userLimit;

    /** 有效期天数（领到后 N 天内有效） */
    private Integer termDays;

    private LocalDateTime termBeginTime;

    private LocalDateTime termEndTime;

    private Long creater;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 非表字段：券的限定范围（课程/分类 id） */
    @TableField(exist = false)
    private java.util.List<Long> scopes;

    /**
     * 非表字段：<b>用户券id</b>（{@code user_coupon.id}）。
     *
     * <p>⚠️ <b>别把它和 {@link #id} 搞混</b>：{@code id} 是<b>券模板</b>id
     * （运营配置的那张券），本字段是"某个用户领到的那一张"的id。</p>
     *
     * <p>为什么需要它：叠加推荐算出的方案要告诉调用方"用了哪几张券"，
     * 而下游（下单记 coupon_ids、支付后核销）需要的是<b>用户券id</b> ——
     * 核销是按 {@code user_coupon.id} 更新状态的。
     * 本项目就踩过：方案里回的是券模板id，核销时
     * {@code UPDATE ... WHERE id IN (模板id)} 永远影响 0 行，
     * 而且<b>不报错</b>，只是券一直停在未使用。</p>
     *
     * <p>由 {@code queryMyAvailableCoupons} 等查询通过
     * {@code SELECT uc.id AS userCouponId} 回填。</p>
     */
    @TableField(exist = false)
    private Long userCouponId;
}
