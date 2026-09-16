package com.fitmind.trade.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 退款申请分页查询。
 *
 * <p>学员端只用自己的 {@code userId} 过滤（服务端强制加，前端传不了），
 * 管理端可以按状态、订单号、明细号筛。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "退款申请分页查询")
public class RefundApplyPageQuery extends PageQuery {

    @Schema(description = "退款申请id")
    private Long id;

    @Schema(description = "订单id")
    private Long orderId;

    @Schema(description = "订单明细id")
    private Long orderDetailId;

    @Schema(description = "退款状态：1-待审批 2-已取消 3-同意退款 4-已拒绝 5-退款成功 6-退款失败")
    private Integer refundStatus;
}
