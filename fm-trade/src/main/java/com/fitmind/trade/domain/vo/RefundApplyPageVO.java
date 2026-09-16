package com.fitmind.trade.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 退款申请列表项（管理端审批列表 / 学员端申请记录）。
 *
 * <p>比 {@link RefundApplyVO} 精简：列表页不需要"原支付单号、渠道流水"这类细节。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "退款申请列表项")
public class RefundApplyPageVO {

    @Schema(description = "退款申请id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "订单id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    @Schema(description = "订单明细id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderDetailId;

    @Schema(description = "课程名（快照）")
    private String courseName;

    @Schema(description = "退款金额（分）")
    private Integer refundAmount;

    @Schema(description = "退款原因")
    private String refundReason;

    @Schema(description = "退款状态：1-待审批 2-已取消 3-同意退款 4-已拒绝 5-退款成功 6-退款失败")
    private Integer status;

    @Schema(description = "状态描述")
    private String statusDesc;

    @Schema(description = "申请时间")
    private LocalDateTime createTime;

    @Schema(description = "审批时间")
    private LocalDateTime approveTime;

    @Schema(description = "完成时间")
    private LocalDateTime finishTime;
}
