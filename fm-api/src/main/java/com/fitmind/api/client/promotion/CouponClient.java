package com.fitmind.api.client.promotion;

import com.fitmind.api.client.promotion.fallback.CouponClientFallback;
import com.fitmind.api.dto.promotion.CouponDiscountDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 促销服务 Feign 客户端。
 *
 * <p>交易侧下单前用它求最优用券方案，支付成功后用它核销券。</p>
 */
@FeignClient(value = "fm-promotion", fallbackFactory = CouponClientFallback.class)
public interface CouponClient {

    @GetMapping("/internal/discount/solution")
    List<CouponDiscountDTO> findSolution(@RequestParam("userId") Long userId,
                                         @RequestParam("courseIds") List<Long> courseIds,
                                         @RequestParam("prices") List<Integer> prices);

    /**
     * 按<b>用户指定的券</b>算优惠（下单时用）。
     *
     * <p>为什么不直接用"最优方案"（findSolution）：前端会把最优方案预勾选推荐给用户，
     * 但用户完全可以自己增减 —— 所以下单时要按"用户最终选的那几张"重算，
     * 而不是要求用户的选择必须等于系统推荐。</p>
     */
    @GetMapping("/internal/discount/detail")
    CouponDiscountDTO queryDetail(@RequestParam("userId") Long userId,
                                  @RequestParam("courseIds") List<Long> courseIds,
                                  @RequestParam("prices") List<Integer> prices,
                                  @RequestParam("userCouponIds") List<Long> userCouponIds);

    @PostMapping("/internal/user-coupons/write-off")
    void writeOff(@RequestBody java.util.Map<String, Object> body);

    @PostMapping("/internal/user-coupons/release")
    void release(@RequestBody List<Long> userCouponIds);
}
