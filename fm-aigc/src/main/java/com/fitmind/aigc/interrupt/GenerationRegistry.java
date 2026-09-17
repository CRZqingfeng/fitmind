package com.fitmind.aigc.interrupt;

import cn.hutool.core.util.StrUtil;
import com.fitmind.aigc.config.AigcProperties;
import com.fitmind.aigc.constants.AigcConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * "这一轮正在生成"的登记簿（分布式打断的底座）。
 *
 * <p>★★ 常见实现的打断是<b>单机</b> {@code ConcurrentHashMap} + <b>没有 TTL</b> +
 * <b>取消时不清理</b>。三者叠加的后果是：<b>用户关掉页面 = 这个会话永久卡死</b>，
 * 而且因为 Map 在内存里，重启才能恢复。</p>
 *
 * <p>本项目改成"Redis 键 + 租约（TTL）+ 多路径清理"，三处差异分别解决三个问题：</p>
 * <table border="1">
 *   <tr><th>改动</th><th>解决什么</th></tr>
 *   <tr><td>Map → Redis</td>
 *       <td>多实例部署：<b>生成跑在实例 A，点"停止"的请求被负载均衡打到实例 B</b> ——
 *           单机 Map 时 B 根本不知道 A 在生成什么，这个场景构不成立</td></tr>
 *   <tr><td>加 TTL（默认 120s）</td>
 *       <td>标记是<b>租约</b>而不是<b>状态</b>：无论进程怎么挂在半路
 *           （被 kill、断连、cancel 没触发、代码抛在 finally 之前），
 *           它都会自动消失。于是"会话永久卡死"在结构上不可能发生</td></tr>
 *   <tr><td>清理点铺满</td>
 *       <td>正常结束 / 出错 / 取消 / 被停止 —— 四条路径都清。TTL 只是最后一道
 *           （如果只靠 TTL，用户要等 2 分钟才能再问）</td></tr>
 * </table>
 *
 * <p>★ <b>值是 requestId 而不是"存在即代表在生成"</b>，这带来了一个额外的好处：
 * <b>同会话连发时，新一轮会覆盖旧一轮的标记</b>，旧一轮在下次检查时发现
 * "值不是自己了"从而主动终止 —— 这正是"新问题接管旧回答"该有的行为，
 * 而不是两条回答交错吐字。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationRegistry {

    /**
     * 原子地"取出并删除"。
     *
     * <p>不用 {@code GETDEL}（Redis 6.2 才有）也不写成"GET 再 DEL"：
     * 后者在并发下会让两个 stop 请求都认为"是我停的"。
     * Lua 脚本在 Redis 里是单线程原子执行的，一个请求拿走值、另一个拿到 null。</p>
     */
    private static final DefaultRedisScript<String> TAKE_AND_DELETE = new DefaultRedisScript<>(
            "local v = redis.call('get', KEYS[1]) "
                    + "if v then redis.call('del', KEYS[1]) end "
                    + "return v", String.class);

    /**
     * 值相等才删。
     *
     * <p>★ 为什么不能直接 DEL：轮次 A 结束得比较晚（比如它在 finally 里排队），
     * 而这时同会话的轮次 B 已经开始并把标记覆盖成自己的了 ——
     * 直接 DEL 会把<b>正在生成的 B</b> 的标记删掉，
     * 表现为"B 的流莫名其妙自己停了"。用"值相等才删"把归属写进清理条件里。</p>
     */
    private static final DefaultRedisScript<Long> CLEAR_IF_MATCH = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final AigcProperties props;

    /** 开始生成：打标记（TTL 自动过期是兜底） */
    public void mark(String sessionId, String requestId) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(requestId)) {
            return;
        }
        try {
            redis.opsForValue().set(AigcConstants.generatingKey(sessionId), requestId,
                    Duration.ofSeconds(props.getStop().getTtlSeconds()));
        } catch (Exception e) {
            // ★ 打标记失败不等于"不能对话"。降级方向要选对：
            //   这里失败的结果是"停止按钮不生效（流会自然结束）"，
            //   而抛出去的结果是"整个对话不可用" —— 明显前者可接受
            log.warn("生成标记写入失败（停止按钮本轮不生效，不影响对话）：sessionId={}, 原因={}",
                    sessionId, e.getMessage());
        }
    }

    /**
     * 我这一轮还"活着"吗？
     *
     * @return true = 可以继续吐字；false = 已被停止 / 已过期 / 已被新一轮接管
     */
    public boolean alive(String sessionId, String requestId) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(requestId)) {
            return true;
        }
        try {
            String current = redis.opsForValue().get(AigcConstants.generatingKey(sessionId));
            if (current == null) {
                // 被 stop 删掉了，或者 TTL 到了（生成慢到超过 TTL，属于异常长尾，
                // 这时终止生成也是合理的 —— 用户早就等不下去了）
                return false;
            }
            return current.equals(requestId);
        } catch (Exception e) {
            // ★ 读失败时选择"继续生成"：Redis 抖动不该把用户的回答掐掉。
            //   宁可"停止按钮偶尔失效"，也不要"Redis 一抖所有回答都中断"
            log.warn("生成标记读取失败（本轮按继续生成处理）：sessionId={}, 原因={}", sessionId, e.getMessage());
            return true;
        }
    }

    /**
     * 停止该会话当前这一轮。
     *
     * @return 被停止的 requestId；null 表示"这里当前没有正在生成的轮次"
     *         （用户点了个已经被停过/已经结束的停止按钮 —— 这是正常情况，不是错误）
     */
    public String stop(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        try {
            String stopped = redis.execute(TAKE_AND_DELETE, List.of(AigcConstants.generatingKey(sessionId)));
            if (stopped == null) {
                log.info("收到停止请求，但该会话当前没有正在生成的轮次：sessionId={}", sessionId);
            } else {
                log.info("★ 收到停止请求：sessionId={}, 被停止的轮次={}", sessionId, stopped);
            }
            return stopped;
        } catch (Exception e) {
            log.warn("停止请求处理失败：sessionId={}, 原因={}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 本轮结束时清理自己的标记（值相等才删，见 {@link #CLEAR_IF_MATCH}） */
    public void clear(String sessionId, String requestId) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(requestId)) {
            return;
        }
        try {
            redis.execute(CLEAR_IF_MATCH, List.of(AigcConstants.generatingKey(sessionId)), requestId);
        } catch (Exception e) {
            // 清理失败也还好：TTL 会兜住（这正是"租约"相对于"状态"的价值）
            log.warn("生成标记清理失败（将由 TTL 兜底）：sessionId={}, 原因={}", sessionId, e.getMessage());
        }
    }
}
