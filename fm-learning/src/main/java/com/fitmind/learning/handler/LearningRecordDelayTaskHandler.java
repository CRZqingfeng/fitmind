package com.fitmind.learning.handler;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fitmind.learning.constants.LearningConstants;
import com.fitmind.learning.domain.po.LearningRecord;
import com.fitmind.learning.mapper.LearningRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 学习进度延迟合并处理器 —— <b>Redis ZSet 分布式实现</b>。
 *
 * <p><b>解决的问题</b>：进度上报是"高频写同一个字段"，直接落库绝大部分是重复覆盖。
 * 本组件把它们合并成「停播后的一次落库」。</p>
 *
 * <p><b>常见实现用 JVM 内的 {@code DelayQueue}，有两个硬伤：</b></p>
 * <ol>
 *   <li>任务在内存里：服务重启，堆积的任务全部丢失（最坏丢进度）</li>
 *   <li>队列不共享：多实例部署时各自为政，扩容后用户请求漂移到别的实例，
 *       同一小节的任务可能被多台机器先后处理</li>
 * </ol>
 *
 * <p><b>本实现用 Redis ZSet，语义不变但分布式安全：</b></p>
 * <pre>
 * 写路径（上报，未完成）：
 *   ① SET 缓存最新进度（TTL 60s）
 *   ② ZADD delay  score=now+delaySeconds  member=lessonId:sectionId
 *      （ZADD 对已存在 member 覆盖 score —— 持续上报时到期时间不断顺延，
 *        这就是"合并"：N 次上报最终只落库 1 次）
 *      ★★ 前提：delaySeconds **必须大于前端上报间隔**，否则任务会在下一次上报之前
 *         就到期被消费，ZADD 变成"新建"而非"覆盖"，合并彻底失效
 *         （实测 5s 时 8 次上报 → 8 次落库；20s 时 → 1 次落库。见《待修复问题清单》#A24）
 *
 * 消费路径（每个实例都跑同一个轮询线程，1 秒一轮）：
 *   ③ ZRANGEBYSCORE delay -inf now LIMIT 50
 *   ④ 对每个 member 执行 ZREM —— 原子抢占，返回 1 的实例才处理
 *   ⑤ 读缓存 → UPDATE 落库 → 清缓存
 *   ⑥ 落库失败 → 任务放回队列延后重试（见 retryLater），上限 MAX_RETRY 次
 *
 * 完成路径（看满一半）：不走延迟队列，直接写终态 + 清缓存 + ZREM
 * </pre>
 *
 * <p><b>正确性</b>：</p>
 * <ul>
 *   <li><b>不重</b>：{@code ZREM} 原子抢占 + {@code UPDATE} 幂等</li>
 *   <li><b>不覆盖新值</b>：落库读的是缓存的最新值，不是任务携带的旧值</li>
 *   <li><b>不丢</b>：任务在 Redis（服务重启不丢）+ 落库失败会重新入队。
 *       ★ 注意"不丢"有边界：缓存失效（TTL 过期 / 被完成路径清掉）时这条断点仍会丢，
 *       但一定会打 {@code warn} 留痕，不再静默</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LearningRecordDelayTaskHandler {

    /** 落库失败后的最大重试次数。超过则放弃并告警 —— 防止 MySQL 长时间不可用时的重试风暴 */
    private static final int MAX_RETRY = 3;

    /** 落库失败后重新入队的退避时长（毫秒） */
    private static final long RETRY_BACKOFF_MILLIS = 10_000L;

    private final StringRedisTemplate redisTemplate;
    private final LearningRecordMapper recordMapper;

    /**
     * 停播后多久落库（秒）。
     *
     * <p>★ <b>必须大于前端上报间隔</b> —— 这是"合并"成立的前提：每次上报都会把 score
     * 覆盖成「上报时刻 + 本值」，只有本值 > 上报间隔，下一次上报才赶得上在到期前顺延它。</p>
     */
    @Value("${learning.record.delay-seconds:20}")
    private long delaySeconds;

    /** 进度缓存 TTL（秒）。必须大于 delaySeconds，否则任务到期时读不到缓存 */
    @Value("${learning.record.cache-ttl-seconds:60}")
    private long cacheTtlSeconds;

    /** 缓存与任务用的 JSON 工具（记录含 LocalDateTime，需注册时间模块） */
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** 消费线程存活标记（优雅停机用） */
    private volatile boolean running = true;

    @PostConstruct
    public void start() {
        Thread worker = new Thread(this::loop, "learning-record-delay-worker");
        worker.setDaemon(true);
        worker.start();
        log.info("分布式进度合并已启动：delay={}s, cacheTTL={}s", delaySeconds, cacheTtlSeconds);
    }

    @PreDestroy
    public void stop() {
        running = false;
        log.info("分布式进度合并已停止");
    }

    /**
     * 登记延迟任务（上报进度时调用，未完成才有必要进队列）。
     *
     * <p><b>注意这一步有两种语义</b>：member 不存在 → <b>新建</b>；member 已存在 → <b>覆盖 score</b>
     * （到期时间顺延）。"合并"只在"覆盖"这一支上成立，而它要求
     * {@code delaySeconds > 上报间隔} —— 详见类注释与《待修复问题清单》#A24。</p>
     */
    public void addDelayTask(LearningRecord record) {
        String member = memberKey(record.getLessonId(), record.getSectionId());
        double score = System.currentTimeMillis() + delaySeconds * 1000;
        redisTemplate.opsForZSet().add(LearningConstants.RECORD_DELAY_KEY, member, score);
        log.debug("进度任务已登记（顺延到 {}）：{}", score, member);
    }

    /**
     * 写进度缓存。
     */
    public void writeCache(LearningRecord record) {
        try {
            redisTemplate.opsForValue().set(cacheKey(record.getLessonId(), record.getSectionId()),
                    mapper.writeValueAsString(record), Duration.ofSeconds(cacheTtlSeconds));
        } catch (Exception e) {
            // 缓存失败不影响主流程：任务到期读不到缓存会跳过（并留 warn），进度由下次上报补齐
            log.warn("进度缓存写入失败：{}", e.getMessage());
        }
    }

    /**
     * 读进度缓存（断点续播查询也走这里，未命中再查库）。
     */
    public LearningRecord readCache(Long lessonId, Long sectionId) {
        String json = redisTemplate.opsForValue().get(cacheKey(lessonId, sectionId));
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return mapper.readValue(json, LearningRecord.class);
        } catch (Exception e) {
            log.warn("进度缓存解析失败，按未命中处理：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 批量读进度缓存（断点续播查询用）。
     *
     * <p><b>为什么用 MGET 而不是循环 {@link #readCache}</b>：一节课有 N 个动作，
     * 逐个 {@code GET} 会把"1 次 DB 查询"退化成 N 次 Redis 往返；MGET 只发一条命令。</p>
     *
     * @return sectionId → 缓存记录（<b>只含命中的项</b>，未命中的不在 map 里）
     */
    public Map<Long, LearningRecord> readCaches(Long lessonId, Collection<Long> sectionIds) {
        if (sectionIds == null || sectionIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = new ArrayList<>(sectionIds);
        List<String> keys = ids.stream().map(id -> cacheKey(lessonId, id)).toList();
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        if (values == null) {
            return Map.of();
        }
        Map<Long, LearningRecord> result = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            String json = values.get(i);
            if (StringUtils.isBlank(json)) {
                continue;
            }
            try {
                result.put(ids.get(i), mapper.readValue(json, LearningRecord.class));
            } catch (Exception e) {
                log.warn("进度缓存解析失败，按未命中处理：lessonId={}, sectionId={}, err={}",
                        lessonId, ids.get(i), e.getMessage());
            }
        }
        return result;
    }

    /**
     * 清除进度缓存（落库完成或完成后调用）。
     */
    public void cleanCache(Long lessonId, Long sectionId) {
        redisTemplate.delete(cacheKey(lessonId, sectionId));
        redisTemplate.delete(retryKey(lessonId, sectionId));
        redisTemplate.opsForZSet().remove(LearningConstants.RECORD_DELAY_KEY,
                memberKey(lessonId, sectionId));
    }

    // ==================== 消费线程 ====================

    private void loop() {
        while (running) {
            try {
                consumeExpired();
            } catch (Exception e) {
                // 单轮失败只记日志，下一轮继续——消费线程绝不能死
                log.error("进度合并消费异常：{}", e.getMessage(), e);
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void consumeExpired() {
        double now = System.currentTimeMillis();
        Set<String> members = redisTemplate.opsForZSet()
                .rangeByScore(LearningConstants.RECORD_DELAY_KEY, 0, now, 0, 50);
        if (members == null || members.isEmpty()) {
            return;
        }
        for (String member : members) {
            // ④ 原子抢占：ZREM 返回数量 > 0 才说明本实例抢到了处理权。
            //    多实例同时 range 到同一个 member 时，只有一个 ZREM 会成功。
            Long removed = redisTemplate.opsForZSet().remove(LearningConstants.RECORD_DELAY_KEY, member);
            if (removed == null || removed == 0) {
                continue;
            }
            String[] parts = member.split(":");
            if (parts.length != 2) {
                log.warn("非法任务 member，已丢弃：{}", member);
                continue;
            }
            Long lessonId = Long.valueOf(parts[0]);
            Long sectionId = Long.valueOf(parts[1]);

            LearningRecord cached = readCache(lessonId, sectionId);
            if (cached == null || cached.getId() == null) {
                // 缓存没了：说明已走完成路径被清理，或缓存 TTL 内被再次清理过。
                // ★ 这里必须留痕 —— 它是"断点静默丢失"的唯一入口，不记日志线上无从排查
                log.warn("进度合并任务到期但缓存已失效，本条断点未落库：member={}", member);
                cleanRetryCounter(lessonId, sectionId);
                continue;
            }

            // ⑤ 落库：只更新 moment（未完成态），UPDATE by id 幂等
            try {
                recordMapper.updateById(new LearningRecord()
                        .setId(cached.getId())
                        .setMoment(cached.getMoment()));
            } catch (Exception e) {
                // ★ 补偿：ZREM 已经把任务摘掉了，此处若直接放弃，这条断点就永久丢失
                retryLater(member, lessonId, sectionId, cached, e);
                continue;
            }
            // ⑤ 落库后清缓存：不清的话缓存会活到 TTL 过期，期间断点续播读到的
            //    是"已落库的旧缓存"，虽然值一样但会掩盖问题；ZSet 已在抢占时移除
            cleanCache(lessonId, sectionId);
            log.debug("合并落库：lessonId={}, sectionId={}, moment={}",
                    lessonId, sectionId, cached.getMoment());
        }
    }

    /**
     * 落库失败后的补偿：把任务放回 ZSet 延后重试，并给进度缓存续一次 TTL。
     *
     * <p><b>为什么必须做</b>：任务在 {@code ZREM} 抢占时就已经从 ZSet 摘掉了，
     * 不补回队列就等于"这条断点永远不落库"（at-most-once）。补回后语义变成
     * "至少尝试 {@value #MAX_RETRY} 次 + UPDATE 幂等"，即 at-least-once。</p>
     *
     * <p><b>为什么要续缓存 TTL</b>：重试发生在退避时长之后，
     * 不续期的话届时缓存早已过期，重试必然又是空跑一趟。</p>
     *
     * <p><b>为什么要限次数</b>：MySQL 长时间不可用时，无上限重试会变成
     * "每 10 秒重试一次"的重试风暴；超过上限后放弃并 error 告警，
     * 断点回退到上一次成功落库的值（可接受，且有日志可查）。</p>
     */
    private void retryLater(String member, Long lessonId, Long sectionId,
                            LearningRecord cached, Exception cause) {
        String key = retryKey(lessonId, sectionId);
        Long retried = redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, Duration.ofMinutes(30));

        if (retried != null && retried > MAX_RETRY) {
            log.error("合并落库连续失败 {} 次，放弃本条断点：member={}, moment={}（续播将回退到上次成功落库的值）",
                    retried - 1, member, cached.getMoment(), cause);
            cleanCache(lessonId, sectionId);
            return;
        }
        redisTemplate.expire(cacheKey(lessonId, sectionId), Duration.ofSeconds(cacheTtlSeconds));
        redisTemplate.opsForZSet().add(LearningConstants.RECORD_DELAY_KEY, member,
                System.currentTimeMillis() + RETRY_BACKOFF_MILLIS);
        log.error("合并落库失败，已重新入队（第 {} 次重试，{}ms 后）：member={}",
                retried, RETRY_BACKOFF_MILLIS, member, cause);
    }

    private void cleanRetryCounter(Long lessonId, Long sectionId) {
        redisTemplate.delete(retryKey(lessonId, sectionId));
    }

    private static String cacheKey(Long lessonId, Long sectionId) {
        return LearningConstants.RECORD_CACHE_KEY + lessonId + ":" + sectionId;
    }

    private static String retryKey(Long lessonId, Long sectionId) {
        return LearningConstants.RECORD_RETRY_KEY + lessonId + ":" + sectionId;
    }

    private static String memberKey(Long lessonId, Long sectionId) {
        return lessonId + ":" + sectionId;
    }
}
