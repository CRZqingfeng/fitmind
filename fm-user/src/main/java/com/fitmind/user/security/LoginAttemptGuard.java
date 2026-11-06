package com.fitmind.user.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;

/**
 * 登录失败计数器（Redis）—— P0-7。
 *
 * <p><b>防的是什么</b>：撞库 / 暴力猜密码。BCrypt 让单次校验慢（~100ms），
 * 但"慢"不是"防"：没有任何次数上限时，攻击者可以持续尝试直到命中，
 * 而本项目此前<b>一条计数都没有</b>。</p>
 *
 * <p>★★ <b>为什么计数点在 fm-user 的密码校验处</b>：
 * 这里的"密码错误"是<b>唯一确定</b>的失败事实。放到网关或 fm-auth 都会把
 * "用户服务不可用"混进"登录失败"——一次下游抖动就可能把一批账号锁在门外，
 * 而症状是"大家都登不进来"，几乎没人会联想到"是被锁号了"。</p>
 *
 * <p>★★ <b>为什么用 INCR 而不是 GET 再 SET</b>：
 * 并发下 GET→SET 会丢计数（5 个请求都读到 0，各自写 1，实际只算 1 次）。
 * 这里的要求是"不能少计"，所以计数必须原子。
 * 用 Lua 还能把"首次设 TTL"和自增合成一次往返，避免"自增成功但 TTL 没设上"
 * 导致计数永不过期 —— 那会让账号被<b>永久</b>锁定。</p>
 *
 * <p><b>为什么锁定期是固定 15 分钟而不是指数退避</b>：
 * 指数退避对"真的忘了密码"的正常用户惩罚过重（第 6 次错要等半小时以上）。
 * 固定 15 分钟窗口已能把"每秒 10 次"压到"每小时 240 次"，
 * 配合 BCrypt 的单次成本，攻击收益已经极低。</p>
 *
 * <p><b>★ Redis 故障时的策略：放行（fail-open）</b>。
 * 计数失败只记 warn，不阻塞登录。理由：登录是入口功能，
 * 为"防盗"而让<b>所有人</b>登不进来，代价远大于"这一轮没限住"。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginAttemptGuard {

    /** 允许的最大连续失败次数 */
    private static final int MAX_ATTEMPTS = 5;

    /** 计数与锁定的时间窗口 */
    private static final Duration WINDOW = Duration.ofMinutes(15);

    /** Redis key 前缀（与 JWT 的 jwt:uid:* 区分开） */
    private static final String KEY_PREFIX = "login:fail:";

    /**
     * 自增 + 首次设置 TTL，一次原子完成。
     *
     * <p>只在 {@code n == 1} 时设 TTL：这样"15 分钟窗口"从<b>第一次失败</b>开始算，
     * 而不是每次失败都续期（续期会让持续攻击者把窗口无限延长，正常用户也被锁更久）。</p>
     */
    private static final DefaultRedisScript<Long> HIT = new DefaultRedisScript<>("""
            local n = redis.call('incr', KEYS[1])
            if n == 1 then redis.call('expire', KEYS[1], ARGV[1]) end
            return n
            """, Long.class);

    private final StringRedisTemplate redis;

    /**
     * 计数键。
     *
     * <p>★ 用"用户名/手机号"而不是 userId：撞库发生在<b>登录之前</b>，那时还没有 userId。
     * 这也意味着一个不存在的账号同样会被计数——这是对的：否则"能不能无限试"
     * 本身就泄露了"这个账号存不存在"。</p>
     *
     * @return null 表示拿不到可用标识（参数缺失），此时不做限制（下游会抛参数异常）
     */
    public String keyOf(String username, String cellPhone) {
        String id;
        if (StringUtils.hasText(username)) {
            // 用户名大小写不敏感，统一小写，避免 Abc/abc 被当成两个账号绕过计数
            id = username.trim().toLowerCase();
        } else if (StringUtils.hasText(cellPhone)) {
            id = cellPhone.trim();
        } else {
            return null;
        }
        return KEY_PREFIX + id;
    }

    /** 是否已超限（进入锁定期） */
    public boolean isBlocked(String key) {
        if (key == null) {
            return false;
        }
        String v;
        try {
            v = redis.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("登录失败计数读取失败，本次按未锁定处理：key={}", key, e);
            return false;
        }
        if (v == null) {
            return false;
        }
        try {
            return Integer.parseInt(v) >= MAX_ATTEMPTS;
        } catch (NumberFormatException e) {
            // 脏值不阻塞登录：宁可放过一次，也不要把用户永久挡在门外
            log.warn("登录失败计数格式异常，已忽略：key={}, value={}", key, v);
            return false;
        }
    }

    /** 记一次失败 */
    public void onFailure(String key) {
        if (key == null) {
            return;
        }
        try {
            Long n = redis.execute(HIT, List.of(key), String.valueOf(WINDOW.toSeconds()));
            if (n != null && n >= MAX_ATTEMPTS) {
                log.warn("登录失败次数达到上限，已锁定 {} 分钟：key={}, 累计={}",
                        WINDOW.toMinutes(), key, n);
            }
        } catch (Exception e) {
            // ★ 计数失败不能影响登录本身
            log.warn("登录失败计数写入失败（不影响本次登录结果）：key={}", key, e);
        }
    }

    /** 登录成功：清零。不清零的话"累计失败"会在若干天后突然把正常用户锁住 */
    public void onSuccess(String key) {
        if (key == null) {
            return;
        }
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("登录失败计数清理失败（会随 TTL 自动过期）：key={}", key, e);
        }
    }
}
