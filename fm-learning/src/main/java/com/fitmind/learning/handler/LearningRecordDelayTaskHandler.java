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
import java.util.Set;

/**
 * 学习进度延迟合并处理器 —— <b>Redis ZSet 分布式实现</b>。
 *
 * <p><b>解决的问题</b>：播放中前端每 15 秒上报一次进度，一段 40 分钟的视频就是 160 次写库，
 * 而绝大多数是同一字段的重复更新。本组件把它们合并成「停播后的一次落库」。</p>
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
 *   ① HSET 缓存最新进度（TTL 30s）
 *   ② ZADD delay  score=now+5s  member=lessonId:sectionId
 *      （ZADD 对已存在 member 覆盖 score —— 持续播放时到期时间不断顺延，
 *        这就是"合并"：N 次上报最终只落库 1 次）
 *
 * 消费路径（每个实例都跑同一个轮询线程，1 秒一轮）：
 *   ③ ZRANGEBYSCORE delay -inf now LIMIT 50
 *   ④ 对每个 member 执行 ZREM —— 原子抢占，返回 1 的实例才处理
 *   ⑤ 读缓存 → UPDATE 落库 → 清缓存
 *
 * 完成路径（看满一半）：不走延迟队列，直接写终态 + 清缓存 + ZREM
 * </pre>
 *
 * <p><b>正确性</b>：不丢（先缓存后登记，任务在 Redis）、不重（ZREM 原子抢占 + UPDATE 幂等）、
 * 不覆盖新值（落库读的是缓存的最新值，不是任务携带的旧值）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LearningRecordDelayTaskHandler {

    private final StringRedisTemplate redisTemplate;
    private final LearningRecordMapper recordMapper;

    /** 停播后多久落库（秒）。播放中每次上报都会顺延到期时间，本值只影响落库延迟 */
    @Value("${learning.record.delay-seconds:5}")
    private long delaySeconds;

    /** 进度缓存 TTL（秒）。必须大于「上报间隔 + 延迟时长」，否则任务到期读不到缓存 */
    @Value("${learning.record.cache-ttl-seconds:30}")
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
            // 缓存失败不影响主流程：任务到期读不到缓存会跳过，进度由下次上报补齐
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
     * 清除进度缓存（落库完成或完成后调用）。
     */
    public void cleanCache(Long lessonId, Long sectionId) {
        redisTemplate.delete(cacheKey(lessonId, sectionId));
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
                // 缓存没了：说明已走完成路径被清理，或缓存 TTL 内被再次清理过，直接跳过
                continue;
            }
            // ⑤ 落库：只更新 moment（未完成态），UPDATE by id 幂等
            recordMapper.updateById(new LearningRecord().setId(cached.getId()).setMoment(cached.getMoment()));
            // ⑤ 落库后清缓存：不清的话缓存会活到 TTL 过期，期间断点续播读到的
            //    是"已落库的旧缓存"，虽然值一样但会掩盖问题；ZSet 已在抢占时移除
            cleanCache(lessonId, sectionId);
            log.debug("合并落库：lessonId={}, sectionId={}, moment={}",
                    lessonId, sectionId, cached.getMoment());
        }
    }

    private static String cacheKey(Long lessonId, Long sectionId) {
        return LearningConstants.RECORD_CACHE_KEY + lessonId + ":" + sectionId;
    }

    private static String memberKey(Long lessonId, Long sectionId) {
        return lessonId + ":" + sectionId;
    }
}
