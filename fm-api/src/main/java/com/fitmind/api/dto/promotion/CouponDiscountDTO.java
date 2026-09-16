package com.fitmind.api.dto.promotion;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

/**
 * 优惠方案（fm-promotion → fm-trade 的契约）。
 */
@Data
@Accessors(chain = true)
public class CouponDiscountDTO {

    /** 使用的用户券id */
    private List<Long> ids;

    private List<String> rules;

    /** 总优惠（分） */
    private Integer discountAmount;

    /** 每门课分摊到的优惠：courseId → 金额（分） */
    private Map<Long, Integer> discountDetail;
}
