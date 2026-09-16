package com.fitmind.trade.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 我的订单分页查询。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "订单分页查询")
public class OrderPageQuery extends PageQuery {

    @Schema(description = "订单状态：1-待支付 2-已支付 3-已关闭 5-已报名 6-退款中；不传查全部")
    private Integer status;
}
