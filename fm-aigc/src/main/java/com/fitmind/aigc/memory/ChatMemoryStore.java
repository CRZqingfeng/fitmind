package com.fitmind.aigc.memory;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fitmind.aigc.config.AigcProperties;
import com.fitmind.aigc.constants.AigcConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆的 Redis 存取层。
 *
 * <p>只做一件事：<b>把"最近几轮对话"当成一个定长滑动窗口来维护</b>。
 * 不做摘要、不做裁剪决策 —— 那些属于 {@code ConversationMemory} 与 {@code ContextCompressor}。</p>
 *
 * <p>★★ <b>与常见实现的三处关键差异</b>（都在方法注释里逐条说明了原因）：</p>
 * <ol>
 *   <li>{@link #recent} 用 {@code LRANGE -N -1}（真正的最新 N 条），
 *       而不是 {@code LRANGE 0 N}（取到的是<b>最旧</b>的 N+1 条 —— 常见实现的 bug）</li>
 *   <li>{@link #append} 带 {@code LTRIM} 上限与 TTL（常见实现两者都没有，列表会无限增长）</li>
 *   <li>所有方法<b>失败即降级</b>（返回空 / 只记日志），不让记忆成为对话的可用性瓶颈</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMemoryStore {

    private final StringRedisTemplate redis;
    private final AigcProperties props;

    /**
     * 追加一条消息。
     *
     * <p>★ <b>一条消息一次写入</b>，而不是"整个列表读出来、改完再写回去"：
     * 后者在并发下会丢更新（两个请求各自读到 5 条、各自写回 6 条）。
     * 这与 M6-1 会话 {@code msg_count} 用 SQL 自增而不是"读+写"是同一个理由。</p>
     */
    public void append(String sessionId, String role, String content) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(content) || !props.getMemory().isEnabled()) {
            return;
        }
        try {
            String key = AigcConstants.memoryKey(sessionId);
            redis.opsForList().rightPush(key, JSONUtil.createObj()
                    .set("role", role)
                    .set("content", content)
                    .toString());
            // ★ 兜底上限：正常情况下压缩（ContextCompressor）会让列表远小于它，
            //   它防的是"压缩关了/失败了 → 列表无限增长"，而那种情况下
            //   在 Redis 内存报警之前不会有任何症状。
            redis.opsForList().trim(key, -props.getMemory().getMaxMessages(), -1);
            // ★ TTL 必须每次刷新：否则"聊了一小时"的会话会在第 7 天从中间断掉
            redis.expire(key, Duration.ofDays(props.getMemory().getTtlDays()));
        } catch (Exception e) {
            // 记忆写入失败不能影响对话 —— 最坏的结果是"下一轮少了一条历史"，
            // 而抛出去的结果是"这一轮直接报错"，两者严重程度差得远
            log.warn("会话记忆写入失败（不影响本轮对话）：sessionId={}, 原因={}", sessionId, e.getMessage());
        }
    }

    /**
     * 取<b>最近</b> limit 条消息。
     *
     * <p>★★ <b>这里就是修正常见实现 A3 缺陷的那一行。</b>
     * 常见实现写的是 {@code LRANGE 0 limit} —— Redis 的索引 0 是列表<b>头部</b>，
     * 而头部是最旧的消息，所以它取到的是"最旧的 N+1 条"，
     * 而且比要求的多一条（{@code LRANGE 0 N} 是闭区间，共 N+1 条）。</p>
     *
     * <p>后果非常隐蔽：<b>对话前几轮一切正常</b>（因为总共还没超过 N 条），
     * 一旦轮数变多，模型看到的就是"很久以前的那几句"，
     * 而用户问的"刚才那个呢"永远得不到正确回应 —— 表现为"AI 记性越来越差"，
     * 排查时容易往模型能力上找原因。</p>
     *
     * <p>正确写法是负索引：{@code LRANGE -N -1} = "从倒数第 N 条到末尾"。
     * 列表长度不足 N 时 Redis 会自动收敛到头部，不需要额外判断。</p>
     */
    public List<MemoryMessage> recent(String sessionId, int limit) {
        if (StrUtil.isBlank(sessionId) || limit <= 0 || !props.getMemory().isEnabled()) {
            return List.of();
        }
        try {
            return parse(redis.opsForList().range(AigcConstants.memoryKey(sessionId), -limit, -1));
        } catch (Exception e) {
            log.warn("会话记忆读取失败（本轮退化为无历史对话）：sessionId={}, 原因={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /** 取<b>最旧</b>的 count 条（供压缩使用；这是唯一需要"从头部取"的地方） */
    public List<MemoryMessage> oldest(String sessionId, int count) {
        if (StrUtil.isBlank(sessionId) || count <= 0) {
            return List.of();
        }
        try {
            return parse(redis.opsForList().range(AigcConstants.memoryKey(sessionId), 0, count - 1L));
        } catch (Exception e) {
            log.warn("会话记忆读取失败：sessionId={}, 原因={}", sessionId, e.getMessage());
            return List.of();
        }
    }

    /** 当前列表长度（压缩触发判断用） */
    public long size(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return 0L;
        }
        try {
            Long n = redis.opsForList().size(AigcConstants.memoryKey(sessionId));
            return n == null ? 0L : n;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 只保留最近 keep 条，其余丢弃。
     *
     * <p>★ 压缩之后调用它 —— 注意这是<b>"丢掉原文"而不是"打标记跳过"</b>。
     * 这是双层记忆结构带来的自由：被丢掉的内容在 MySQL 的 {@code chat_message} 里
     * 一条都不少（用户翻历史、做数据分析都看得到），
     * 丢掉的只是"这一轮要喂给模型的那份"。常见实现因为只有一层，
     * 压缩时必须小心翼翼不能丢数据，结果就变成了"宁可留着也不压"。</p>
     */
    public void keepLast(String sessionId, int keep) {
        if (StrUtil.isBlank(sessionId) || keep <= 0) {
            return;
        }
        try {
            redis.opsForList().trim(AigcConstants.memoryKey(sessionId), -keep, -1);
        } catch (Exception e) {
            log.warn("会话记忆裁剪失败：sessionId={}, 原因={}", sessionId, e.getMessage());
        }
    }

    /** 清空一个会话的记忆（含摘要）。目前只被"数据清理"用到，但它是完整的一对读写 */
    public void clear(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return;
        }
        try {
            redis.delete(List.of(AigcConstants.memoryKey(sessionId), AigcConstants.summaryKey(sessionId)));
        } catch (Exception e) {
            log.warn("会话记忆清理失败：sessionId={}, 原因={}", sessionId, e.getMessage());
        }
    }

    /** 已生成的早期对话摘要（没有则返回 null） */
    public String summary(String sessionId) {
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        try {
            return redis.opsForValue().get(AigcConstants.summaryKey(sessionId));
        } catch (Exception e) {
            return null;
        }
    }

    /** 写入/覆盖摘要 */
    public void saveSummary(String sessionId, String summary) {
        if (StrUtil.isBlank(sessionId)) {
            return;
        }
        try {
            String key = AigcConstants.summaryKey(sessionId);
            redis.opsForValue().set(key, summary);
            redis.expire(key, Duration.ofDays(props.getMemory().getTtlDays()));
        } catch (Exception e) {
            log.warn("摘要写入失败：sessionId={}, 原因={}", sessionId, e.getMessage());
        }
    }

    /** 记忆的 TTL 剩余秒数（-2 = key 不存在，-1 = 没有 TTL）。验收要断言"TTL 真的存在" */
    public long ttlSeconds(String sessionId) {
        try {
            Long ttl = redis.getExpire(AigcConstants.memoryKey(sessionId));
            return ttl == null ? -2L : ttl;
        } catch (Exception e) {
            return -2L;
        }
    }

    private List<MemoryMessage> parse(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<MemoryMessage> list = new ArrayList<>(raw.size());
        for (String s : raw) {
            try {
                JSONObject o = JSONUtil.parseObj(s);
                list.add(new MemoryMessage(o.getStr("role"), o.getStr("content")));
            } catch (Exception e) {
                // 单条脏数据不该让整段历史作废（JSON 格式如果将来变过，
                // 老数据会在这里被跳过，而不是把这一轮对话打成"没有历史"）
                log.debug("跳过无法解析的记忆条目：{}", StrUtil.brief(s, 60));
            }
        }
        return list;
    }
}
