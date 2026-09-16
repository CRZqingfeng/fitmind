package com.fitmind.trade.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单。
 *
 * <p><b>★ 订单 id 由前端预生成</b>（{@code IdType.ASSIGN_ID} 但值从请求里带过来）——
 * 用户点"提交订单"前，前端先调预下单接口拿到一个雪花 id，再带着它提交。
 * 好处是下单接口<b>天然幂等</b>：同一份请求重复提交，第二次会被主键挡住，
 * 不会产生两笔订单。这是常见实现 {@code prePlaceOrder} 的设计，很值得学。</p>
 *
 * <p>⚠️ 表名是 {@code trade_order} 而不是 {@code order} ——
 * MySQL 里 ORDER 是保留字，MyBatis-Plus 不会自动加反引号。</p>
 */
@Data
@Accessors(chain = true)
@TableName(value = "trade_order", autoResultMap = true)
public class TradeOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 订单id（前端预生成的雪花id，不是数据库自增） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 状态，取值见 {@link com.fitmind.trade.constants.OrderStatus} */
    private Integer status;

    /** 商品总额（分） */
    private Integer totalAmount;

    /** 优惠金额（分），M4-3 起生效 */
    private Integer discountAmount;

    /** 实付金额（分）= 总额 - 优惠 */
    private Integer realAmount;

    /** 使用的优惠券id（M4-3） */
    @TableField(value = "coupon_ids", typeHandler = JacksonTypeHandler.class)
    private List<Long> couponIds;

    /** 支付渠道码（M4-2） */
    private String payChannel;

    /** 支付单号（M4-2） */
    private String payOrderNo;

    /** 关闭原因（超时 / 用户取消） */
    private String message;

    private LocalDateTime createTime;

    private LocalDateTime payTime;

    private LocalDateTime closeTime;

    private LocalDateTime updateTime;
}
