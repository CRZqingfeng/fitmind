package com.fitmind.promotion.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.UserContext;
import com.fitmind.promotion.constants.PromotionConstants;
import com.fitmind.promotion.constants.PromotionErrorInfo;
import com.fitmind.promotion.domain.po.Coupon;
import com.fitmind.promotion.domain.po.UserCoupon;
import com.fitmind.promotion.domain.vo.CouponVO;
import com.fitmind.promotion.mapper.CouponMapper;
import com.fitmind.promotion.mapper.UserCouponMapper;
import com.fitmind.promotion.service.IExchangeCodeService;
import com.fitmind.promotion.service.IUserCouponService;
import com.fitmind.promotion.strategy.discount.DiscountStrategy;
import com.fitmind.promotion.utils.CodeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 用户券服务实现（领券 / 兑换 / 核销 / 释放）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCouponServiceImpl extends ServiceImpl<UserCouponMapper, UserCoupon>
        implements IUserCouponService {

    private final CouponMapper couponMapper;
    private final IExchangeCodeService exchangeCodeService;
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> receiveCouponScript;

    // ==================== ★ 秒杀领券 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void receiveCoupon(Long couponId) {
        Long userId = UserContext.getUser();
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null) {
            throw new BizIllegalException(PromotionErrorInfo.Msg.COUPON_NOT_EXISTS);
        }
        if (!Integer.valueOf(PromotionConstants.COUPON_ISSUING).equals(coupon.getStatus())) {
            throw new BizIllegalException(PromotionErrorInfo.Msg.COUPON_NOT_ISSUING);
        }
        if (coupon.getIssueBeginTime() != null && coupon.getIssueBeginTime().isAfter(LocalDateTime.now())) {
            throw new BizIllegalException(PromotionErrorInfo.Msg.COUPON_NOT_BEGIN);
        }

        // ★★ 库存 / 有效期 / 每人限领 —— 一次 Lua 原子执行，杜绝超卖
        String couponKey = PromotionConstants.COUPON_CACHE_KEY_PREFIX + couponId;
        String userKey = PromotionConstants.COUPON_USER_LIMIT_KEY_PREFIX + couponId;
        Long r = redisTemplate.execute(receiveCouponScript,
                List.of(couponKey, userKey), userId.toString());

        if (r == null || r != 0) {
            String msg = switch (r == null ? -1 : r.intValue()) {
                case 1 -> PromotionErrorInfo.Msg.COUPON_NOT_EXISTS;
                case 2 -> PromotionErrorInfo.Msg.COUPON_SOLD_OUT;
                case 3 -> PromotionErrorInfo.Msg.COUPON_EXPIRED;
                case 4 -> String.format(PromotionErrorInfo.Msg.COUPON_USER_LIMIT, coupon.getUserLimit());
                default -> PromotionErrorInfo.Msg.COUPON_RECEIVE_BUSY;
            };
            log.info("领券失败：userId={}, couponId={}, 原因码={}", userId, couponId, r);
            throw new BizIllegalException(msg);
        }

        // 通过后写用户券
        saveUserCoupon(coupon, userId);
        log.info("领券成功：userId={}, couponId={}", userId, couponId);
    }

    // ==================== ★ 兑换码兑换 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void exchangeCoupon(String code) {
        Long userId = UserContext.getUser();

        // 1. ★ 算法校验（不查库）：格式错或校验码对不上都会在这里抛异常
        long serialNum = CodeUtil.parseCode(code);

        // 2. ★ bitmap 判断并标记已兑换：SETBIT 返回的是**旧值**，
        //    旧值为 true 说明别人已经兑换过了 —— 这是幂等/防重的关键
        Boolean before = redisTemplate.opsForValue()
                .getBit(PromotionConstants.COUPON_CODE_MAP_KEY, serialNum);
        if (Boolean.TRUE.equals(before)) {
            throw new BizIllegalException(PromotionErrorInfo.Msg.CODE_EXCHANGED);
        }
        exchangeCodeService.markExchanged(serialNum);

        // 3. 查出这张券
        Long couponId = exchangeCodeService.queryTargetId(serialNum);
        if (couponId == null) {
            throw new BizIllegalException(PromotionErrorInfo.Msg.CODE_INVALID);
        }
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null) {
            throw new BizIllegalException(PromotionErrorInfo.Msg.CODE_INVALID);
        }
        if (!Integer.valueOf(PromotionConstants.OBTAIN_CODE).equals(coupon.getObtainWay())) {
            throw new BizIllegalException(PromotionErrorInfo.Msg.CODE_NOT_EXCHANGE_WAY);
        }
        if (coupon.getIssueEndTime() != null && coupon.getIssueEndTime().isBefore(LocalDateTime.now())) {
            throw new BizIllegalException(PromotionErrorInfo.Msg.COUPON_EXPIRED);
        }

        saveUserCoupon(coupon, userId);
        log.info("兑换码兑换成功：userId={}, code={}, serialNum={}, couponId={}",
                userId, code, serialNum, couponId);
    }

    // ==================== 查询 / 核销 / 释放 ====================

    @Override
    public List<CouponVO> queryMyCoupons(Integer status) {
        Long userId = UserContext.getUser();
        List<UserCoupon> list = list(new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .eq(status != null, UserCoupon::getStatus, status)
                .orderByDesc(UserCoupon::getId));
        if (CollectionUtils.isEmpty(list)) {
            return List.of();
        }
        List<CouponVO> res = new ArrayList<>(list.size());
        LocalDateTime now = LocalDateTime.now();
        for (UserCoupon uc : list) {
            Coupon c = couponMapper.selectById(uc.getCouponId());
            if (c == null) {
                continue;
            }
            boolean expired = uc.getTermEndTime() != null && uc.getTermEndTime().isBefore(now);
            res.add(new CouponVO()
                    .setId(uc.getId())
                    .setCouponId(c.getId())
                    .setName(c.getName())
                    .setDiscountType(c.getDiscountType())
                    .setRule(DiscountStrategy.getDiscount(c.getDiscountType()).getRule(c))
                    .setSpecific(Integer.valueOf(1).equals(c.getSpecific()))
                    .setStatus(uc.getStatus())
                    .setTermBeginTime(uc.getTermBeginTime())
                    .setTermEndTime(uc.getTermEndTime())
                    .setExpired(expired));
        }
        return res;
    }

    @Override
    public void writeOffCoupons(List<Long> userCouponIds, Long orderId) {
        if (CollectionUtils.isEmpty(userCouponIds)) {
            return;
        }
        // ⚠️ 走 baseMapper 而不是 ServiceImpl.update(Wrapper)：前者能拿到**影响行数**。
        //    "核销"这种改变状态的操作必须看清影响了几行 ——
        //    影响 0 行说明"券已经不在未使用状态了"，那是**幂等命中**还是**条件写错了**，
        //    只看"方法执行完了"是分辨不出来的。
        int n = baseMapper.update(null, new LambdaUpdateWrapper<UserCoupon>()
                .set(UserCoupon::getStatus, PromotionConstants.USER_COUPON_USED)
                .set(UserCoupon::getOrderId, orderId)
                .set(UserCoupon::getUsedTime, LocalDateTime.now())
                .in(UserCoupon::getId, userCouponIds)
                // ★ 条件：只核销"未使用"的。重复调用（MQ 重投）不会二次生效
                .eq(UserCoupon::getStatus, PromotionConstants.USER_COUPON_UNUSED));
        if (n == 0) {
            log.warn("核销优惠券影响 0 行（券可能已核销/已失效，或 id 对不上）：ids={}, orderId={}",
                    userCouponIds, orderId);
        } else {
            log.info("核销优惠券：ids={}, orderId={}, 影响 {} 行", userCouponIds, orderId, n);
        }
    }

    @Override
    public void releaseCoupons(List<Long> userCouponIds) {
        if (CollectionUtils.isEmpty(userCouponIds)) {
            return;
        }
        int n = baseMapper.update(null, new LambdaUpdateWrapper<UserCoupon>()
                .set(UserCoupon::getStatus, PromotionConstants.USER_COUPON_UNUSED)
                .set(UserCoupon::getOrderId, null)
                .set(UserCoupon::getUsedTime, null)
                .in(UserCoupon::getId, userCouponIds)
                .eq(UserCoupon::getStatus, PromotionConstants.USER_COUPON_USED));
        log.info("释放优惠券（订单取消/支付失败）：ids={}, 影响 {} 行", userCouponIds, n);
    }

    /** 写一张用户券，并按券规则算出有效期 */
    private void saveUserCoupon(Coupon coupon, Long userId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime termEnd;
        if (coupon.getTermDays() != null && coupon.getTermDays() > 0) {
            termEnd = now.plusDays(coupon.getTermDays());
        } else {
            termEnd = coupon.getTermEndTime();
        }
        save(new UserCoupon()
                .setId(IdUtil.getSnowflakeNextId())
                .setCouponId(coupon.getId())
                .setUserId(userId)
                .setStatus(PromotionConstants.USER_COUPON_UNUSED)
                .setTermBeginTime(now)
                .setTermEndTime(termEnd));
    }
}
