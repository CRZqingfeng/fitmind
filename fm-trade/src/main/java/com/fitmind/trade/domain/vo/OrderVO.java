package com.fitmind.trade.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.experimental.Accessors;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单（列表 / 详情共用）。
 */
@Data
@Accessors(chain = true)
@Schema(description = "订单")
public class OrderVO {

    @Schema(description = "订单id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "状态值：1-待支付 2-已支付 3-已关闭 5-已报名 6-退款中")
    private Integer status;

    @Schema(description = "状态描述（前端直接显示，不用自己映射数字）")
    private String statusDesc;

    @Schema(description = "商品总额（分）")
    private Integer totalAmount;

    @Schema(description = "优惠金额（分）")
    private Integer discountAmount;

    @Schema(description = "实付金额（分）")
    private Integer realAmount;

    @Schema(description = "关闭原因（超时 / 用户取消），已关闭的订单才有")
    private String message;

    @Schema(description = "下单时间")
    private LocalDateTime createTime;

    @Schema(description = "支付时间")
    private LocalDateTime payTime;

    @Schema(description = "关闭时间")
    private LocalDateTime closeTime;

    @Schema(description = "支付截止时间（待支付的订单才有）")
    private LocalDateTime payOutTime;

    /**
     * 能不能取消 / 能不能支付，由<b>服务端</b>算好。
     *
     * <p>让前端拿 status 数字自己判断"2 是不是能付"这种逻辑，
     * 一旦后端加了新状态（比如 M4-4 的"退款中"），前端就得跟着改。
     * 服务端直接给布尔值，前端只负责渲染按钮。</p>
     */
    @Schema(description = "是否可以继续支付")
    private Boolean canPay;

    @Schema(description = "是否可以取消")
    private Boolean canCancel;

    @Schema(description = "订单明细")
    private List<OrderDetailVO> details;
}
