package com.fitmind.promotion.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.promotion.domain.po.UserCoupon;
import com.fitmind.promotion.domain.vo.CouponVO;

import java.util.List;

/**
 * 用户券服务。
 */
public interface IUserCouponService extends IService<UserCoupon> {

    /**
     * ★ 领取优惠券（秒杀场景）。
     *
     * <p>库存、有效期、每人限领三步判断由 Lua 脚本在 Redis 里<b>一次原子执行</b>。</p>
     */
    void receiveCoupon(Long couponId);

    /**
     * 用兑换码兑换。
     *
     * <p>兑换码的合法性由<b>算法</b>验证（不查库），
     * 是否已兑换由 <b>Redis bitmap</b> 标记（也是一个原子操作）。</p>
     */
    void exchangeCoupon(String code);

    /** 我的优惠券（按状态筛选，含券规则描述） */
    List<CouponVO> queryMyCoupons(Integer status);

    /**
     * 核销：订单支付成功后把券置为已使用。
     *
     * @param userCouponIds 本次使用的用户券id
     * @param orderId       订单id
     */
    void writeOffCoupons(List<Long> userCouponIds, Long orderId);

    /** 释放：订单取消/支付失败后把券还回去 */
    void releaseCoupons(List<Long> userCouponIds);
}
