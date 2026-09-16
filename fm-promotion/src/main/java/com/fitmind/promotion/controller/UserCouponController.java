package com.fitmind.promotion.controller;

import com.fitmind.promotion.domain.vo.CouponVO;
import com.fitmind.promotion.service.IUserCouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 我的优惠券 / 领券 / 兑换。
 */
@RestController
@RequestMapping("/user-coupons")
@RequiredArgsConstructor
@Tag(name = "我的优惠券")
public class UserCouponController {

    private final IUserCouponService userCouponService;

    @Operation(summary = "★ 领取优惠券（秒杀：库存/限领由 Lua 原子判断）")
    @Parameters(@Parameter(name = "couponId", description = "券模板id"))
    @PostMapping("/{couponId}/receive")
    public void receive(@PathVariable("couponId") Long couponId) {
        userCouponService.receiveCoupon(couponId);
    }

    @Operation(summary = "★ 用兑换码兑换")
    @PostMapping("/exchange/{code}")
    public void exchange(@PathVariable("code") String code) {
        userCouponService.exchangeCoupon(code);
    }

    @Operation(summary = "我的优惠券（可按状态筛选）")
    @GetMapping
    public List<CouponVO> myCoupons(@RequestParam(value = "status", required = false) Integer status) {
        return userCouponService.queryMyCoupons(status);
    }
}
