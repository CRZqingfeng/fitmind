package com.fitmind.promotion.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.promotion.domain.po.Coupon;

/**
 * 券模板服务（运营侧）。
 */
public interface ICouponService extends IService<Coupon> {

    /**
     * 新增券并把它的库存等信息<b>预热到 Redis</b>。
     *
     * <p>★ 这一步不能省：秒杀领券的 Lua 脚本直接读 Redis 里的库存。
     * 如果券没预热，脚本会返回"券不存在"，用户永远领不到。</p>
     */
    Long saveCoupon(Coupon coupon);

    /** 开始发放（把券置为发放中，并写入 Redis 库存） */
    void beginIssue(Long couponId);

    /** 预热券信息到 Redis（领取前必须调用） */
    void cacheCoupon(Coupon coupon);
}
