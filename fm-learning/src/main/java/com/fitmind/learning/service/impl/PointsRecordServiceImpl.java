package com.fitmind.learning.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.common.utils.UserContext;
import com.fitmind.learning.constants.RedisConstants;
import com.fitmind.learning.domain.po.PointsRecord;
import com.fitmind.learning.domain.vo.PointsStatisticsVO;
import com.fitmind.learning.enums.PointsRecordType;
import com.fitmind.learning.mapper.PointsRecordMapper;
import com.fitmind.learning.service.IPointsRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 积分实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsRecordServiceImpl extends ServiceImpl<PointsRecordMapper, PointsRecord>
        implements IPointsRecordService {

    private static final DateTimeFormatter BOARD_KEY_SUFFIX = DateTimeFormatter.ofPattern("yyyyMM");

    private final StringRedisTemplate redisTemplate;

    @Override
    public void addPointsRecord(Long userId, int points, PointsRecordType type, String bizId) {
        // ==================== 1.每日上限校验（含补差额） ====================
        int realPoints = points;
        int maxPoints = type.getMaxPoints();
        if (maxPoints > 0) {
            LocalDateTime begin = LocalDate.now().atStartOfDay();
            int todayPoints = sumTodayPoints(userId, type, begin, begin.plusDays(1));
            if (todayPoints >= maxPoints) {
                // 已到上限：整笔丢弃（防刷）
                log.info("积分已达每日上限，丢弃：userId={}, type={}, 今日已得={}/{}",
                        userId, type.getDesc(), todayPoints, maxPoints);
                return;
            }
            // 超出上限：只补差额。
            // 为什么不是整笔丢弃：用户已得 45（上限 50），再来一笔 +10，
            // 整笔丢弃等于白干；补 5 分既守住上限也不吃亏。
            if (todayPoints + points > maxPoints) {
                realPoints = maxPoints - todayPoints;
            }
        }

        // ==================== 2.落明细（唯一索引保证幂等） ====================
        PointsRecord record = new PointsRecord()
                .setUserId(userId)
                .setType(type.getValue())
                .setPoints(realPoints)
                .setBizId(bizId);
        try {
            save(record);
        } catch (DuplicateKeyException e) {
            // ★ MQ 是 at-least-once，重复投递必然发生。
            //   常见实现的积分没有防重，重复消费会重复加分；
            //   这里靠 (user_id, type, biz_id) 唯一索引兜底：
            //   明细不多一行，下面的榜单也不加分。
            log.info("积分幂等拦截（消息重复投递）：userId={}, type={}, bizId={}",
                    userId, type.getDesc(), bizId);
            return;
        }

        // ==================== 3.同步实时榜 ====================
        // 明细可追溯，榜单供查询——两者在同一方法内完成，避免不一致窗口
        String key = boardKey(LocalDate.now());
        redisTemplate.opsForZSet().incrementScore(key, userId.toString(), realPoints);
        // 兜底 TTL：正常情况下月度结算任务会清理，这里防止任务失败导致 key 永久堆积
        redisTemplate.expire(key, Duration.ofDays(90));
        log.info("积分到账：userId={}, type={}, points={}, bizId={}",
                userId, type.getDesc(), realPoints, bizId);
    }

    @Override
    public List<PointsStatisticsVO> queryMyPointsToday() {
        Long userId = UserContext.getUser();
        LocalDateTime begin = LocalDate.now().atStartOfDay();
        LocalDateTime end = begin.plusDays(1);

        List<PointsStatisticsVO> list = new ArrayList<>();
        for (PointsRecordType type : PointsRecordType.values()) {
            PointsStatisticsVO vo = new PointsStatisticsVO();
            vo.setType(type.getValue());
            vo.setTypeDesc(type.getDesc());
            vo.setMaxPoints(type.getMaxPoints());
            vo.setPoints(sumTodayPoints(userId, type, begin, end));
            list.add(vo);
        }
        return list;
    }

    // ==================== 内部方法 ====================

    private int sumTodayPoints(Long userId, PointsRecordType type, LocalDateTime begin, LocalDateTime end) {
        return lambdaQuery()
                .eq(PointsRecord::getUserId, userId)
                .eq(PointsRecord::getType, type.getValue())
                .ge(PointsRecord::getCreateTime, begin)
                .lt(PointsRecord::getCreateTime, end)
                .list()
                .stream()
                .mapToInt(r -> r.getPoints() == null ? 0 : r.getPoints())
                .sum();
    }

    private String boardKey(LocalDate date) {
        return RedisConstants.POINTS_BOARD_KEY_PREFIX + date.format(BOARD_KEY_SUFFIX);
    }
}
