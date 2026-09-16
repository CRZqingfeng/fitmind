package com.fitmind.promotion.controller;

import com.fitmind.promotion.service.IUserCouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 用户券内部接口（供交易服务调用）。
 *
 * <p>核销放在<b>支付成功后</b>（而不是下单时）—— 因为下单可能不付款，
 * 提前核销会把用户的券锁死在一笔注定关闭的订单上。</p>
 */
@RestController
@RequestMapping("/internal/user-coupons")
@RequiredArgsConstructor
@Tag(name = "用户券内部接口（供交易服务调用）")
public class UserCouponInternalController {

    private final IUserCouponService userCouponService;

    /** 核销：支付成功后调用 */
    @Operation(summary = "核销优惠券")
    @PostMapping("/write-off")
    public void writeOff(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Long> ids = ((List<Object>) body.get("ids")).stream()
                .map(o -> Long.valueOf(String.valueOf(o))).toList();
        Long orderId = Long.valueOf(String.valueOf(body.get("orderId")));
        userCouponService.writeOffCoupons(ids, orderId);
    }

    /** 释放：订单取消/关闭后调用 */
    @Operation(summary = "释放优惠券")
    @PostMapping("/release")
    public void release(@RequestBody List<Long> userCouponIds) {
        userCouponService.releaseCoupons(userCouponIds);
    }
}
