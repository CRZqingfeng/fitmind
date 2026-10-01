package com.fitmind.promotion.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.promotion.constants.PromotionConstants;
import com.fitmind.promotion.domain.po.Coupon;
import com.fitmind.promotion.domain.po.ExchangeCode;
import com.fitmind.promotion.mapper.CouponMapper;
import com.fitmind.promotion.mapper.ExchangeCodeMapper;
import com.fitmind.promotion.service.IExchangeCodeService;
import com.fitmind.promotion.utils.CodeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 兑换码服务实现。
 *
 * <p><b>★ 这里最能体现"20 亿兑换码"的思路</b>：
 * 生成 100 万个码，数据库里确实会多 100 万行（用于后台查看/导出），
 * 但<b>校验一个码只需要一次 Redis 位运算</b>，跟总量无关。
 * 而且码本身是算出来的，不是存出来的 —— 即使不入库，算法也能验证它合不合法。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeCodeServiceImpl extends ServiceImpl<ExchangeCodeMapper, ExchangeCode>
        implements IExchangeCodeService {

    private final CouponMapper couponMapper;
    private final StringRedisTemplate redisTemplate;

    @Override
    public List<String> generateCodes(Long couponId) {
        Coupon coupon = couponMapper.selectById(couponId);
        if (coupon == null || coupon.getTotalNum() == null || coupon.getTotalNum() <= 0) {
            return List.of();
        }
        int total = coupon.getTotalNum();

        // 1. ★ Redis 一次性自增出一段序号（原子，多个实例同时生成也不会重号）
        Long maxSerial = redisTemplate.opsForValue()
                .increment(PromotionConstants.COUPON_CODE_SERIAL_KEY, total);
        if (maxSerial == null) {
            throw new IllegalStateException("兑换码序号生成失败");
        }
        long begin = maxSerial - total + 1;

        // 2. 用算法算出每一个码
        List<ExchangeCode> list = new ArrayList<>(total);
        List<String> codes = new ArrayList<>(total);
        for (long serial = begin; serial <= maxSerial; serial++) {
            String code = CodeUtil.generateCode(serial, couponId);
            codes.add(code);
            list.add(new ExchangeCode()
                    .setId(serial)                      // ★ 主键就是序列号
                    .setCode(code)
                    .setExchangeTargetId(couponId)
                    .setExpiredTime(coupon.getIssueEndTime())
                    .setStatus(PromotionConstants.CODE_UNUSED));
        }

        // 3. 入库（供后台查看/导出；校验时其实用不到它）
        saveBatch(list);

        // 4. ★ 记录"这张券用掉了哪段序号"：ZSet member=券id，score=最大序号
        //    查"序号 s 属于哪张券"时，取第一个 score >= s 的即可
        redisTemplate.opsForZSet()
                .add(PromotionConstants.COUPON_RANGE_KEY, couponId.toString(), maxSerial);

        log.info("生成兑换码：couponId={}, 序号 [{}, {}], 共 {} 个", couponId, begin, maxSerial, total);
        return codes;
    }

    @Override
    public Long queryTargetId(long serialNum) {
        // 1. 先查 ZSet（Redis）：第一个 score >= serialNum 的券，就是它
        Set<String> results = redisTemplate.opsForZSet()
                .rangeByScore(PromotionConstants.COUPON_RANGE_KEY, serialNum, serialNum + 5000L, 0L, 1L);
        if (results != null && !results.isEmpty()) {
            return Long.parseLong(results.iterator().next());
        }
        // 2. Redis 没有（可能被清了）→ 回数据库查
        return baseMapper.queryTargetId(serialNum);
    }

    @Override
    public boolean markExchanged(long serialNum) {
        // ★ bitmap：一个码只占 1 bit。20 亿个码 ≈ 256MB，与券的数量无关
        // ★★ 用 SETBIT 的**返回值（旧值）**判定，不再"先 GETBIT 再 SETBIT"：
        //    后者是两步、中间有窗口 —— 两个并发请求同时 GETBIT 都会读到 false，双双"通过判重"。
        //    SETBIT 在 Redis 单线程里是一个原子操作，"判重 + 抢占"一次完成，天然防并发。
        Boolean previous = redisTemplate.opsForValue()
                .setBit(PromotionConstants.COUPON_CODE_MAP_KEY, serialNum, true);
        // 旧值为 true = 之前已有人兑换过 → 本次没抢到
        return !Boolean.TRUE.equals(previous);
    }

    @Override
    public void releaseExchanged(long serialNum) {
        // ★ 补偿操作：把占位放回去，让用户能重试。
        //   为什么需要它 —— bitmap 在 Redis、发券在 MySQL，而 **Redis 不参与 MySQL 事务**：
        //   MySQL 回滚时 bit 不会跟着回滚，这就是 #A17（码被白白吃掉）的成因。
        redisTemplate.opsForValue()
                .setBit(PromotionConstants.COUPON_CODE_MAP_KEY, serialNum, false);
    }
}
