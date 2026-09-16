package com.fitmind.trade.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 退款详情（学员端 / 管理端共用）。
 *
 * <p>把"订单 + 明细 + 退款申请"三张表里用户会关心的字段拼在一起，
 * 前端一个接口就能渲染出完整的退款详情页。其中的课程名、价格都取
 * <b>订单明细里的快照</b>（不是课程表的当前值）—— 退款详情是交易凭证的一部分，
 * 必须显示"当时买的是什么、花了多少"。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "退款详情")
public class RefundApplyVO {

    @Schema(description = "退款申请id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "订单id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    @Schema(description = "订单明细id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderDetailId;

    // ==================== 退的哪门课（快照） ====================

    @Schema(description = "课程id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long courseId;

    @Schema(description = "课程名（下单时的快照）")
    private String courseName;

    @Schema(description = "封面（快照）")
    private String coverUrl;

    @Schema(description = "课程原价（分，快照）")
    private Integer price;

    @Schema(description = "分摊到的优惠（分）")
    private Integer discountAmount;

    @Schema(description = "★ 实付金额（分）—— 退款金额就是它")
    private Integer realPayAmount;

    // ==================== 退款过程 ====================

    @Schema(description = "退款金额（分）")
    private Integer refundAmount;

    @Schema(description = "退款状态：1-待审批 2-已取消 3-同意退款 4-已拒绝 5-退款成功 6-退款失败")
    private Integer status;

    @Schema(description = "状态描述")
    private String statusDesc;

    @Schema(description = "状态说明（给用户看的一句话）")
    private String message;

    @Schema(description = "退款原因")
    private String refundReason;

    @Schema(description = "问题说明")
    private String questionDesc;

    @Schema(description = "申请时间")
    private LocalDateTime createTime;

    @Schema(description = "审批意见")
    private String approveOpinion;

    @Schema(description = "审批时间")
    private LocalDateTime approveTime;

    @Schema(description = "退款单号（渠道侧）")
    private String refundOrderNo;

    @Schema(description = "退款渠道")
    private String refundChannel;

    @Schema(description = "退款失败原因")
    private String failedReason;

    @Schema(description = "退款完成时间（成功或失败）")
    private LocalDateTime finishTime;

    // ==================== 原支付信息 ====================

    @Schema(description = "原支付单号")
    private String payOrderNo;

    @Schema(description = "原支付渠道")
    private String payChannel;

    @Schema(description = "下单时间")
    private LocalDateTime orderTime;

    @Schema(description = "支付成功时间")
    private LocalDateTime paySuccessTime;
}
