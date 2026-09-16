package com.fitmind.data.service.impl;

import com.fitmind.data.constants.DataConstants;
import com.fitmind.data.constants.DataMetric;
import com.fitmind.data.domain.po.DataCourseDaily;
import com.fitmind.data.domain.po.DataDaily;
import com.fitmind.data.mapper.DataCourseDailyMapper;
import com.fitmind.data.mapper.DataDailyMapper;
import com.fitmind.data.service.IDataFlushService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;

/**
 * 日数据落库实现。
 *
 * <p>模式与 M3-2 的积分榜一致：<b>Redis 扛高频写入，定时任务批量落 MySQL</b>。
 * 差别在于积分榜是"赛季末一次性落"，这里是"每天落一次"。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataFlushServiceImpl implements IDataFlushService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern(DataConstants.DAY_PATTERN);

    private final DataDailyMapper dailyMapper;
    private final DataCourseDailyMapper courseDailyMapper;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int flushDaily(LocalDate date) {
        LocalDate target = date == null ? LocalDate.now().minusDays(1) : date;
        int statDate = Integer.parseInt(target.format(DAY_FMT));
        String key = DataConstants.DAILY_KEY_PREFIX + target.format(DAY_FMT);

        Map<Object, Object> values = redisTemplate.opsForHash().entries(key);
        DataDaily daily = dailyMapper.getByDate(statDate);
        boolean insert = daily == null;
        if (insert) {
            daily = new DataDaily().setStatDate(statDate);
        }
        daily.setSignInCount(intOf(values, DataMetric.SIGN_IN_COUNT))
                .setLearnCount(intOf(values, DataMetric.LEARN_COUNT))
                .setPracticeCount(intOf(values, DataMetric.PRACTICE_COUNT))
                .setOrderCount(intOf(values, DataMetric.ORDER_COUNT))
                .setOrderAmount(longOf(values, DataMetric.ORDER_AMOUNT))
                .setRefundCount(intOf(values, DataMetric.REFUND_COUNT))
                .setRefundAmount(longOf(values, DataMetric.REFUND_AMOUNT))
                .setAssessCount(intOf(values, DataMetric.ASSESS_COUNT));

        if (insert) {
            // ★ 完全没有数据的一天不落库：否则"趋势图"会被一堆全 0 的行填满，
            //   把真正有数据的那些天挤得看不清
            if (isAllZero(daily)) {
                log.debug("{} 无任何数据，跳过落库", statDate);
                return 0;
            }
            dailyMapper.insert(daily);
        } else {
            dailyMapper.updateById(daily);
        }

        flushCourseDaily(target);
        log.info("日数据已落库：statDate={}, 订单 {} 笔 / 成交额 {} 分",
                statDate, daily.getOrderCount(), daily.getOrderAmount());
        return 1;
    }

    @Override
    public void clearCounter(LocalDate date) {
        LocalDate target = date == null ? LocalDate.now().minusDays(1) : date;
        String day = target.format(DAY_FMT);
        redisTemplate.delete(DataConstants.DAILY_KEY_PREFIX + day);
        redisTemplate.delete(DataConstants.COURSE_RANK_KEY_PREFIX + day);
        redisTemplate.delete(DataConstants.COURSE_AMOUNT_KEY_PREFIX + day);
        redisTemplate.delete(DataConstants.COURSE_REFUND_KEY_PREFIX + day);
    }

    /** 课程维度落库：从 ZSet（销量）+ hash（金额/退款）合并成一行行记录 */
    private void flushCourseDaily(LocalDate target) {
        int statDate = Integer.parseInt(target.format(DAY_FMT));
        String day = target.format(DAY_FMT);

        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeWithScores(DataConstants.COURSE_RANK_KEY_PREFIX + day, 0, -1);
        if (tuples == null || tuples.isEmpty()) {
            return;
        }
        Map<Object, Object> amounts = redisTemplate.opsForHash()
                .entries(DataConstants.COURSE_AMOUNT_KEY_PREFIX + day);
        Map<Object, Object> refunds = redisTemplate.opsForHash()
                .entries(DataConstants.COURSE_REFUND_KEY_PREFIX + day);

        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() == null) {
                continue;
            }
            Long courseId = Long.valueOf(tuple.getValue());
            int count = tuple.getScore() == null ? 0 : (int) Math.round(tuple.getScore());
            long amount = longOf(amounts, String.valueOf(courseId));
            int refund = (int) longOf(refunds, String.valueOf(courseId));

            DataCourseDaily existed = courseDailyMapper.getByDateAndCourse(statDate, courseId);
            if (existed == null) {
                courseDailyMapper.insert(new DataCourseDaily()
                        .setStatDate(statDate)
                        .setCourseId(courseId)
                        .setOrderCount(count)
                        .setOrderAmount(amount)
                        .setRefundCount(refund));
            } else {
                courseDailyMapper.updateById(new DataCourseDaily()
                        .setId(existed.getId())
                        .setStatDate(statDate)
                        .setCourseId(courseId)
                        .setOrderCount(count)
                        .setOrderAmount(amount)
                        .setRefundCount(refund));
            }
        }
    }

    private boolean isAllZero(DataDaily d) {
        return d.getSignInCount() == 0 && d.getLearnCount() == 0 && d.getPracticeCount() == 0
                && d.getOrderCount() == 0 && d.getOrderAmount() == 0
                && d.getRefundCount() == 0 && d.getRefundAmount() == 0 && d.getAssessCount() == 0;
    }

    private int intOf(Map<Object, Object> values, DataMetric metric) {
        return (int) longOf(values, metric.getField());
    }

    private long longOf(Map<Object, Object> values, DataMetric metric) {
        return longOf(values, metric.getField());
    }

    private long longOf(Map<Object, Object> values, String field) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        Object v = values.get(field);
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            log.warn("计数值无法解析，按 0 处理：{}={}", field, v);
            return 0L;
        }
    }
}
