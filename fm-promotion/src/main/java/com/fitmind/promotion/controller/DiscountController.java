package com.fitmind.promotion.controller;

import com.fitmind.promotion.domain.dto.CouponDiscountDTO;
import com.fitmind.promotion.domain.dto.OrderCourseDTO;
import com.fitmind.promotion.service.IDiscountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 优惠方案（内部接口，供交易服务在下单前查询）。
 */
@RestController
@RequestMapping("/internal/discount")
@RequiredArgsConstructor
@Tag(name = "优惠方案（内部接口）")
public class DiscountController {

    private final IDiscountService discountService;

    @Operation(summary = "按指定的券算优惠（用户手动选券 / 下单时用）")
    @GetMapping("/detail")
    public CouponDiscountDTO detail(@RequestParam("userId") Long userId,
                                    @RequestParam("courseIds") List<Long> courseIds,
                                    @RequestParam("prices") List<Integer> prices,
                                    @RequestParam("userCouponIds") List<Long> userCouponIds) {
        List<OrderCourseDTO> courses = buildCourses(courseIds, prices);
        return discountService.queryDiscountDetail(userCouponIds, courses, userId);
    }

    @Operation(summary = "求最优用券方案")
    @GetMapping("/solution")
    public List<CouponDiscountDTO> solution(@RequestParam("userId") Long userId,
                                            @RequestParam("courseIds") List<Long> courseIds,
                                            @RequestParam("prices") List<Integer> prices) {
        return discountService.findDiscountSolution(buildCourses(courseIds, prices), userId);
    }

    /** 把并行的两个数组拼成课程列表 */
    private List<OrderCourseDTO> buildCourses(List<Long> courseIds, List<Integer> prices) {
        List<OrderCourseDTO> courses = new java.util.ArrayList<>();
        for (int i = 0; i < courseIds.size(); i++) {
            courses.add(new OrderCourseDTO()
                    .setId(courseIds.get(i))
                    .setPrice(i < prices.size() ? prices.get(i) : 0));
        }
        return courses;
    }
}
