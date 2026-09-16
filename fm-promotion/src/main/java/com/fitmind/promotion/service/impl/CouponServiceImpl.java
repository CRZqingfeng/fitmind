package com.fitmind.promotion.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.promotion.constants.PromotionConstants;
import com.fitmind.promotion.domain.po.Coupon;
import com.fitmind.promotion.domain.po.CouponScope;
import com.fitmind.promotion.mapper.CouponMapper;
import com.fitmind.promotion.mapper.CouponScopeMapper;
import com.fitmind.promotion.service.ICouponService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 券模板服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponServiceImpl extends ServiceImpl<CouponMapper, Coupon> implements ICouponService {

    private final CouponScopeMapper couponScopeMapper;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveCoupon(Coupon coupon) {
        save(coupon);
        // 限定范围
        if (!CollectionUtils.isEmpty(coupon.getScopes())) {
            for (Long bizId : coupon.getScopes()) {
                couponScopeMapper.insert(new CouponScope()
                        .setCouponId(coupon.getId())
                        .setBizType(PromotionConstants.SCOPE_COURSE)
                        .setBizId(bizId));
            }
        }
        // ★ 预热到 Redis：秒杀领券的 Lua 脚本直接读它，不查库
        cacheCoupon(coupon);
        log.info("新增优惠券：id={}, name={}, 总量={}, 每人限领={}",
                coupon.getId(), coupon.getName(), coupon.getTotalNum(), coupon.getUserLimit());
        return coupon.getId();
    }

    @Override
    public void beginIssue(Long couponId) {
        Coupon coupon = getById(couponId);
        if (coupon == null) {
            return;
        }
        coupon.setStatus(PromotionConstants.COUPON_ISSUING);
        updateById(coupon);
        cacheCoupon(coupon);
        log.info("优惠券开始发放：id={}", couponId);
    }

    @Override
    public void cacheCoupon(Coupon coupon) {
        String key = PromotionConstants.COUPON_CACHE_KEY_PREFIX + coupon.getId();
        Map<String, String> map = new HashMap<>();
        // 剩余库存（Lua 脚本会 hincrby -1）
        map.put("totalNum", String.valueOf(coupon.getTotalNum()));
        map.put("userLimit", String.valueOf(coupon.getUserLimit()));
        // Lua 里用 redis.call('time')[1] 取的是秒级时间戳，这里保持一致
        map.put("issueEndTime",
                String.valueOf(coupon.getIssueEndTime().atZone(java.time.ZoneId.systemDefault())
                        .toInstant().getEpochSecond()));
        redisTemplate.opsForHash().putAll(key, map);
        log.info("优惠券信息已预热到 Redis：key={}, {}", key, map);
    }
}
