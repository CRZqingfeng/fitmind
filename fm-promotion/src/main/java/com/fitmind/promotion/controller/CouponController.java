package com.fitmind.promotion.controller;

import com.fitmind.promotion.domain.po.Coupon;
import com.fitmind.promotion.service.ICouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 券模板接口（运营侧）。
 */
@RestController
@RequestMapping("/coupons")
@RequiredArgsConstructor
@Tag(name = "优惠券管理（运营）")
public class CouponController {

    private final ICouponService couponService;

    @Operation(summary = "新增优惠券（同时预热库存到 Redis）")
    @PostMapping
    public Long save(@RequestBody Coupon coupon) {
        return couponService.saveCoupon(coupon);
    }

    @Operation(summary = "开始发放（置为发放中并再次预热）")
    @PutMapping("/{id}/issue")
    public void beginIssue(@PathVariable("id") Long id) {
        couponService.beginIssue(id);
    }
}
