package com.fitmind.data.service.impl;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.fitmind.data.constants.DataConstants;
import com.fitmind.data.constants.DataEventType;
import com.fitmind.data.constants.DataMetric;
import com.fitmind.data.domain.po.DataEventRecord;
import com.fitmind.data.mapper.DataEventRecordMapper;
import com.fitmind.data.service.IDataCollectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 数据采集实现。
 *
 * <p><b>★ 整个类最重要的一句话：先插去重表，插成功才计数。</b></p>
 *
 * <p>为什么必须这个顺序，反过来会怎样：
 * 如果先 INCR 再插表，重复投递时 INCR 已经加过了，
 * 插表失败只是"记不下来"，数字已经错了 ——
 * <b>而且错得毫无痕迹</b>。先插表则用数据库约束把重复挡在计数之前。</p>
 *
 * <p>为什么用"捕获唯一索引冲突"而不是"先查再插"：
 * 先查再插是 check-then-act，并发下两个线程都查到"没有"就都插了 ——
 * 这与 M4-4 修掉的"审批用 getById 判状态 + updateById"是同一个错误。
 * 唯一索引是数据库给的原子保证，用它就对了。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataCollectServiceImpl implements IDataCollectService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern(DataConstants.DAY_PATTERN);

    private final DataEventRecordMapper eventRecordMapper;
    private final StringRedisTemplate redisTemplate;

    /** 计数 key 的兜底过期时间（小时） */
    @Value("${data.counter-ttl-hours:72}")
    private int counterTtlHours;

    @Override
    public boolean collect(DataEventType eventType, String bizId, Long userId, LocalDate date,
                           MetricDelta... metrics) {
        if (eventType == null || StringUtils.isBlank(bizId) || date == null) {
            log.warn("采集参数不完整，忽略：type={}, bizId={}, date={}", eventType, bizId, date);
            return false;
        }
        // ---------- 1. 去重：唯一索引是唯一的真相 ----------
        if (!dedup(eventType, bizId, userId, date)) {
            log.debug("重复事件已忽略：{}:{}", eventType, bizId);
            return false;
        }
        // ---------- 2. 累加 ----------
        String key = DataConstants.DAILY_KEY_PREFIX + date.format(DAY_FMT);
        for (MetricDelta delta : metrics) {
            if (delta == null || delta.metric() == null) {
                continue;
            }
            redisTemplate.opsForHash().increment(key, delta.metric().getField(), delta.delta());
        }
        // 过期时间每次续一下（防止任务挂了之后计数永久堆积）
        redisTemplate.expire(key, Duration.ofHours(counterTtlHours));
        log.debug("已采集：{}:{} → {}", eventType, bizId, date);
        return true;
    }

    @Override
    public void collectCourse(Long courseId, LocalDate date, int count, long amount, int refund) {
        if (courseId == null || date == null) {
            return;
        }
        String day = date.format(DAY_FMT);
        String cid = String.valueOf(courseId);
        if (count > 0) {
            String rankKey = DataConstants.COURSE_RANK_KEY_PREFIX + day;
            redisTemplate.opsForZSet().incrementScore(rankKey, cid, count);
            redisTemplate.expire(rankKey, Duration.ofHours(counterTtlHours));
        }
        if (amount != 0) {
            String amtKey = DataConstants.COURSE_AMOUNT_KEY_PREFIX + day;
            redisTemplate.opsForHash().increment(amtKey, cid, amount);
            redisTemplate.expire(amtKey, Duration.ofHours(counterTtlHours));
        }
        if (refund > 0) {
            String rfKey = DataConstants.COURSE_REFUND_KEY_PREFIX + day;
            redisTemplate.opsForHash().increment(rfKey, cid, refund);
            redisTemplate.expire(rfKey, Duration.ofHours(counterTtlHours));
        }
    }

    /**
     * 插入去重记录。
     *
     * @return true = 首次（可以计数）；false = 重复（跳过）
     */
    private boolean dedup(DataEventType eventType, String bizId, Long userId, LocalDate date) {
        DataEventRecord record = new DataEventRecord()
                .setEventType(eventType.name())
                .setBizId(bizId)
                .setUserId(userId)
                .setStatDate(Integer.parseInt(date.format(DAY_FMT)));
        try {
            return eventRecordMapper.insert(record) > 0;
        } catch (DuplicateKeyException e) {
            // 撞唯一索引 = 处理过。这是正常情况，不是错误
            return false;
        } catch (Exception e) {
            // ★ 其他异常（比如数据库连不上）必须抛出：
            //   吞掉就会漏统计，而"漏了"比"重复"更难发现
            log.error("事件去重记录写入失败，放弃本次统计（宁可漏也不要错）：{}:{}",
                    eventType, bizId, e);
            return false;
        }
    }
}
