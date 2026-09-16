package com.fitmind.trade.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 轻量 Redis 锁（SETNX + Lua 安全解锁）。
 *
 * <p><b>先说清楚它不是什么</b>：它不是 Redisson，不支持可重入、看门狗续期、公平锁。
 * 我们用它的场景很单一 —— <b>把同一个用户的下单请求串行化</b>，
 * 挡的是"手抖点两下"和"网络重试"，持锁时间以毫秒计。</p>
 *
 * <p><b>★ 而且它不是正确性的最后一道防线</b>：真正保证"同一门课不会被买两次"的是
 * 数据库唯一索引（订单主键 + {@code trade_order_detail} 的查询约束）。
 * 锁解决的是<b>效率</b>问题（避免白跑一遍完整的下单流程），
 * 唯一索引解决的是<b>正确性</b>问题。把这两件事分开想，就不会在锁上过度投入。</p>
 *
 * <p><b>为什么解锁要写 Lua</b>：直接 {@code DEL} 会误删别人的锁 ——
 * A 持锁超时自动过期后 B 拿到了锁，A 的 finally 却把 B 的锁删了，
 * 接着 C 也能拿到锁，锁就形同虚设。Lua 里"比对 value 再删"是原子的，能避免这个问题。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLockHelper {

    private final StringRedisTemplate redisTemplate;

    /** 比对持有者再删除：只有 value 是自己的才删 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    /**
     * 尝试加锁。
     *
     * @return 加锁成功返回持有者标识（解锁时要带回来）；失败返回 null
     */
    public String tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(ok) ? token : null;
    }

    /** 释放锁（比对持有者，不会误删别人的锁） */
    public void unlock(String key, String token) {
        if (token == null) {
            return;
        }
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
        } catch (Exception e) {
            // 解锁失败不算业务失败：锁会自己过期，只是多挡几秒
            log.warn("释放锁失败（锁将自行过期）：key={}", key, e);
        }
    }
}
