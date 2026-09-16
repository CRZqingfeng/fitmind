package com.fitmind.promotion.service;

import com.fitmind.promotion.domain.dto.CouponDiscountDTO;
import com.fitmind.promotion.domain.dto.OrderCourseDTO;

import java.util.List;

/**
 * 折扣服务（叠加推荐 + 下单算优惠）。
 */
public interface IDiscountService {

    /**
     * ★ 求最优用券方案（"叠加推荐"）。
     *
     * <p>输入：本次想买的课程；输出：按优惠力度排好序的方案列表。
     * 第一个就是最划算的，前端默认勾选它。</p>
     */
    List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> orderCourses, Long userId);

    /**
     * 按指定的券算优惠（用户手动改选券时用）。
     */
    CouponDiscountDTO queryDiscountDetail(List<Long> userCouponIds,
                                          List<OrderCourseDTO> orderCourses, Long userId);
}
