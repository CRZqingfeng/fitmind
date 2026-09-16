package com.fitmind.pay.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 支付单。
 *
 * <p><b>★ 单号为什么有两个</b>：</p>
 * <ul>
 *   <li>{@code payOrderNo} —— 本系统生成的支付单号，对外暴露（下发给第三方、给用户看）</li>
 *   <li>{@code bizOrderNo} —— 业务订单号（trade_order.id），用于回调后反查业务订单</li>
 * </ul>
 *
 * <p>不直接用业务订单号当支付单号，是因为<b>一笔业务订单可以发起多次支付</b>
 * （第一次支付失败、或者用户换个渠道重试）。两个号分开，重试才有迹可循。</p>
 */
@Data
@Accessors(chain = true)
@TableName("pay_order")
public class PayOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 支付单号（本系统生成，全局唯一） */
    private String payOrderNo;

    /** 业务订单号（trade_order.id） */
    private String bizOrderNo;

    /** 下单用户（冗余：回调时不用回查交易服务） */
    private Long bizUserId;

    private String payChannelCode;

    /** 支付金额（分） */
    private Integer amount;

    /** 状态：1-待支付 2-已支付 3-已关闭 */
    private Integer status;

    private String qrCodeUrl;

    private LocalDateTime paySuccessTime;

    private LocalDateTime expireTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
