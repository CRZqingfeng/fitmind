package com.fitmind.aigc.limit;

import cn.hutool.core.util.StrUtil;
import com.fitmind.aigc.config.AigcProperties;
import com.fitmind.aigc.constants.AigcConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 对话的用户级限流（配额 + 并发）。
 *
 * <p>★★ <b>为什么用"先扣后用、失败退还"而不是"先查再用"</b>：
 * "先查再用"是 check-then-act —— 两个并发请求会同时读到"还剩 1 次"、
 * 然后各自放行，实际用了 2 次。配额恰恰是最怕这种超卖的地方
 * （它直接对应成本）。所以检查和占用必须在 Redis 里<b>一次原子完成</b>（Lua），
 * 这与 M4-3 的秒杀扣库存是同一个手法。</p>
 *
 * <p>★★ <b>为什么"每日次数"之外还要单独管"正在生成的轮数"</b>：
 * 配额管的是<b>总量</b>（今天能用多少次），并发管的是<b>瞬时压力</b>。
 * 只有配额的话，一个用户在几个标签页里同时提问就能瞬间打出十几个模型调用 ——
 * 每一个都"没超配额"，但成本与下游压力是真的。这类问题在演示时很常见
 * （手快连点几下就复现了），而它跟"限额用完"是完全不同的原因，必须分开报。</p>
 *
 * <p>★ <b>降级要退还轮数</b>：模型不可用时用户并没有得到服务，扣他的额度是不公平的。
 * 这里不存在被滥用的空间 —— 反复触发降级换来的是同一句"我忙不过来"，
 * 没有任何价值。（token 不退还：降级时的消耗近似为 0，单独做退还只是增加复杂度。）</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatQuotaLimiter {

    /**
     * 预扣：一次原子完成"检查 + 占用"。
     *
     * <p>返回值约定：{@code >= 0} 放行（值为占用后的今日已用轮数）；
     * {@code -1} 超轮数 / {@code -2} 超 token / {@code -3} 并发过多。</p>
     */
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local used = tonumber(redis.call('get', KEYS[1]) or '0')
            local running = tonumber(redis.call('get', KEYS[2]) or '0')
            local tokens = tonumber(redis.call('get', KEYS[3]) or '0')
            if used >= tonumber(ARGV[1]) then return -1 end
            if tokens >= tonumber(ARGV[2]) then return -2 end
            if running >= tonumber(ARGV[3]) then return -3 end
            redis.call('incr', KEYS[1])
            redis.call('incr', KEYS[2])
            redis.call('expire', KEYS[1], ARGV[4])
            redis.call('expire', KEYS[2], ARGV[5])
            redis.call('expire', KEYS[3], ARGV[4])
            return used + 1
            """, Long.class);

    /**
     * 结算：释放并发 + 累加 token +（可选）退还轮数。
     *
     * <p>它是<b>幂等</b>的：本轮的 {@code running} 减到 0 就不再减
     * （用 {@code if running > 0} 兜住），所以"正常结束"与"取消"两条路径
     * 都调它也不会把计数减成负数 —— 计数一旦为负，配额就永远用不完了。</p>
     */
    private static final DefaultRedisScript<Long> SETTLE = new DefaultRedisScript<>("""
            local running = tonumber(redis.call('get', KEYS[2]) or '0')
            if running > 0 then redis.call('decr', KEYS[2]) end
            if tonumber(ARGV[1]) > 0 then
                redis.call('incrby', KEYS[3], ARGV[1])
                redis.call('expire', KEYS[3], ARGV[3])
            end
            if ARGV[2] == '1' then
                local used = tonumber(redis.call('get', KEYS[1]) or '0')
                if used > 0 then redis.call('decr', KEYS[1]) end
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final AigcProperties props;

    /**
     * 临时额度覆盖（内存态，重启即恢复）。
     *
     * <p>★ 为什么需要它：<b>限流这种东西，不调小就永远验证不到"被限住"的行为。</b>
     * 一天 200 次的额度要去打满 200 轮才能测，而"改 yml + 重启"来调小
     * 又让"改额度立即生效"这件事没法验证。所以给管理端一个临时调额入口 ——
     * 它同时也是运维的真实需求（活动期间临时放宽）。</p>
     *
     * <p>⚠️ 刻意**不持久化**：临时调额是一次操作意图，不是长期配置；
     * 落库的话，一次误操作就永久生效了，而且没人会想起来改回去。</p>
     */
    private final AtomicReference<AigcProperties.Limit> override = new AtomicReference<>();

    /**
     * 申请一次对话额度（预扣）。
     *
     * @return 放行 / 拒绝（拒绝时带上原因，好让上层给出**说人话**的提示）
     */
    public QuotaResult acquire(Long userId) {
        AigcProperties.Limit cfg = limits();
        if (!cfg.isEnabled() || userId == null) {
            // 限流被关掉时返回一个"不受限"的结果，调用方不用到处判空
            return QuotaResult.allow(0, Integer.MAX_VALUE);
        }
        try {
            Long r = redis.execute(ACQUIRE,
                    List.of(chatKey(userId), runningKey(userId), tokenKey(userId)),
                    String.valueOf(cfg.getDailyChats()),
                    String.valueOf(cfg.getDailyTokens()),
                    String.valueOf(cfg.getMaxConcurrent()),
                    String.valueOf(ttlSeconds()),
                    String.valueOf(cfg.getRunningTtlSeconds()));
            long code = r == null ? -1L : r;
            if (code == -1L) {
                return QuotaResult.deny(QuotaResult.CHAT_LIMIT, (int) usedToday(userId), cfg.getDailyChats());
            }
            if (code == -2L) {
                return QuotaResult.deny(QuotaResult.TOKEN_LIMIT, (int) usedToday(userId), cfg.getDailyChats());
            }
            if (code == -3L) {
                log.info("用户并发超限：userId={}, 正在生成={}", userId, running(userId));
                return QuotaResult.deny(QuotaResult.CONCURRENCY, (int) usedToday(userId), cfg.getDailyChats());
            }
            return QuotaResult.allow((int) code, cfg.getDailyChats());
        } catch (Exception e) {
            // ★ 限流组件故障时**放行**，而不是拦下所有人。
            //   取舍很清楚：Redis 抖动不该让整个 AI 功能不可用；
            //   代价是"这段时间没有限额"，而限额是成本控制、不是安全边界。
            log.warn("配额检查失败（本轮放行，不因限流组件故障影响对话）：userId={}, 原因={}",
                    userId, e.getMessage());
            return QuotaResult.allow(0, cfg.getDailyChats());
        }
    }

    /**
     * 结算本轮（释放并发、累加 token，降级时退还轮数）。
     *
     * @param tokens  本轮实际消耗的 token（拿不到就传 0）
     * @param refund  是否退还这一轮的额度（模型不可用/请求根本没跑起来时为 true）
     */
    public void settle(Long userId, int tokens, boolean refund) {
        AigcProperties.Limit cfg = limits();
        if (!cfg.isEnabled() || userId == null) {
            return;
        }
        try {
            redis.execute(SETTLE, List.of(chatKey(userId), runningKey(userId), tokenKey(userId)),
                    String.valueOf(Math.max(0, tokens)),
                    refund ? "1" : "0",
                    String.valueOf(ttlSeconds()));
        } catch (Exception e) {
            // 结算失败最坏的结果是"并发额度被占住"—— 而它有自己的短 TTL 兜底，
            // 不会永久泄漏（这就是给 running 单独设 TTL 的原因）
            log.warn("配额结算失败（并发额度将由 TTL 兜底释放）：userId={}, 原因={}", userId, e.getMessage());
        }
    }

    /** 当前配额状态（给前端显示"今日还剩 N 次"，也是验收的观测口） */
    public QuotaView view(Long userId) {
        AigcProperties.Limit cfg = limits();
        return new QuotaView(cfg.isEnabled(), (int) usedToday(userId), cfg.getDailyChats(),
                (int) tokensToday(userId), cfg.getDailyTokens(), (int) running(userId),
                cfg.getMaxConcurrent());
    }

    /** 管理端临时调额（只影响本进程，重启恢复） */
    public void override(int dailyChats, int dailyTokens, int maxConcurrent) {
        AigcProperties.Limit cfg = limits();
        AigcProperties.Limit next = new AigcProperties.Limit();
        next.setEnabled(cfg.isEnabled());
        next.setDailyChats(dailyChats);
        next.setDailyTokens(dailyTokens);
        next.setMaxConcurrent(maxConcurrent);
        next.setRunningTtlSeconds(cfg.getRunningTtlSeconds());
        override.set(next);
        log.warn("★ 限流额度已被临时调整：{} 轮 / {} token / 并发 {}（重启后恢复配置值）",
                dailyChats, dailyTokens, maxConcurrent);
    }

    /** 恢复配置值 */
    public void resetOverride() {
        override.set(null);
        log.info("限流额度已恢复为配置值");
    }

    /** 清空某个用户当天的计数（验收/演示用：不必等第二天） */
    public void reset(Long userId) {
        if (userId == null) {
            return;
        }
        redis.delete(List.of(chatKey(userId), runningKey(userId), tokenKey(userId)));
    }

    // ==================== 内部 ====================

    private AigcProperties.Limit limits() {
        AigcProperties.Limit custom = override.get();
        return custom != null ? custom : props.getLimit();
    }

    private long usedToday(Long userId) {
        return num(redis.opsForValue().get(chatKey(userId)));
    }

    private long tokensToday(Long userId) {
        return num(redis.opsForValue().get(tokenKey(userId)));
    }

    private long running(Long userId) {
        return num(redis.opsForValue().get(runningKey(userId)));
    }

    private long num(String v) {
        try {
            return StrUtil.isBlank(v) ? 0L : Long.parseLong(v.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 计数的 TTL：到今天结束再多留 1 小时。
     *
     * <p>★ 为什么是"到今天结束"而不是固定 24 小时：配额是<b>按自然日</b>重置的
     * （用户理解的"今天"是日历上的今天）。固定 24 小时会让"上午 10 点用满、
     * 第二天上午 10 点才恢复"，与用户的预期不符。
     * 多留的 1 小时是为了避免"23:59:30 的请求在 00:00:00 刚好把 key 过期掉"。
     */
    private long ttlSeconds() {
        LocalDateTime now = LocalDateTime.now();
        long seconds = Duration.between(now.toLocalTime(), LocalTime.MAX).getSeconds() + 3600;
        return Math.max(60, seconds);
    }

    private String chatKey(Long userId) {
        return AigcConstants.QUOTA_KEY_PREFIX + today() + ":chat:" + userId;
    }

    private String runningKey(Long userId) {
        return AigcConstants.QUOTA_KEY_PREFIX + today() + ":running:" + userId;
    }

    private String tokenKey(Long userId) {
        return AigcConstants.QUOTA_KEY_PREFIX + today() + ":token:" + userId;
    }

    private String today() {
        return LocalDateTime.now().format(AigcConstants.DAY_FMT);
    }

    /** 配额快照 */
    public record QuotaView(boolean enabled, int usedChats, int dailyChats, int usedTokens,
                            int dailyTokens, int running, int maxConcurrent) {
    }
}
