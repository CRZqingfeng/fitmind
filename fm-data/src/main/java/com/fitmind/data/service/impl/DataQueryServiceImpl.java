package com.fitmind.data.service.impl;

import com.fitmind.data.constants.DataConstants;
import com.fitmind.data.constants.DataErrorInfo;
import com.fitmind.data.constants.DataMetric;
import com.fitmind.data.domain.po.DataCourseDaily;
import com.fitmind.data.domain.po.DataDaily;
import com.fitmind.data.domain.vo.CourseTopVO;
import com.fitmind.data.domain.vo.OverviewVO;
import com.fitmind.data.domain.vo.TrendVO;
import com.fitmind.data.mapper.DataCourseDailyMapper;
import com.fitmind.data.mapper.DataDailyMapper;
import com.fitmind.data.service.IDataFlushService;
import com.fitmind.data.service.IDataQueryService;
import com.fitmind.common.exceptions.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 查询实现。
 *
 * <p><b>设计要点：今天读 Redis、历史读 MySQL</b>。</p>
 *
 * <p>为什么不能只读 MySQL：今天的计数还在 Redis 里没落库，
 * 运营看板看不到"今天卖了多少"就没意义了。
 * 为什么不能只读 Redis：Redis 的 key 落完库就被清掉了（见
 * {@link IDataFlushService#clearCounter}），历史数据只能查 MySQL。</p>
 *
 * <p>所以两条路都得走 —— 这也是 VO 里要有 {@code source} 字段的原因：
 * <b>实时值和归档值的语义不同，必须让用户看得见差别</b>。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQueryServiceImpl implements IDataQueryService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern(DataConstants.DAY_PATTERN);

    private final DataDailyMapper dailyMapper;
    private final DataCourseDailyMapper courseDailyMapper;
    private final IDataFlushService flushService;
    private final StringRedisTemplate redisTemplate;

    /** 默认榜单项数 */
    @Value("${data.top-n:10}")
    private int topN;

    @Override
    public OverviewVO overview(LocalDate date) {
        LocalDate target = date == null ? LocalDate.now() : date;
        int statDate = Integer.parseInt(target.format(DAY_FMT));

        // ---------- 历史：查 MySQL ----------
        DataDaily archived = dailyMapper.getByDate(statDate);
        if (archived != null) {
            return toVO(archived, "archived");
        }

        // ---------- 今天：查 Redis ----------
        Map<Object, Object> values = redisTemplate.opsForHash()
                .entries(DataConstants.DAILY_KEY_PREFIX + target.format(DAY_FMT));
        if (values == null || values.isEmpty()) {
            return new OverviewVO().setStatDate(statDate).setSource("empty");
        }
        OverviewVO vo = new OverviewVO()
                .setStatDate(statDate)
                .setSignInCount(intOf(values, DataMetric.SIGN_IN_COUNT))
                .setLearnCount(intOf(values, DataMetric.LEARN_COUNT))
                .setPracticeCount(intOf(values, DataMetric.PRACTICE_COUNT))
                .setOrderCount(intOf(values, DataMetric.ORDER_COUNT))
                .setOrderAmount(longOf(values, DataMetric.ORDER_AMOUNT))
                .setRefundCount(intOf(values, DataMetric.REFUND_COUNT))
                .setRefundAmount(longOf(values, DataMetric.REFUND_AMOUNT))
                .setAssessCount(intOf(values, DataMetric.ASSESS_COUNT))
                .setSource("realtime");
        return vo;
    }

    @Override
    public List<CourseTopVO> courseTop(LocalDate date, int limit) {
        LocalDate target = date == null ? LocalDate.now() : date;
        int statDate = Integer.parseInt(target.format(DAY_FMT));
        int n = limit <= 0 ? topN : Math.min(limit, 100);

        String day = target.format(DAY_FMT);
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeWithScores(DataConstants.COURSE_RANK_KEY_PREFIX + day, 0, n - 1L);

        List<CourseTopVO> list = new ArrayList<>();
        if (tuples != null && !tuples.isEmpty()) {
            Map<Object, Object> amounts = redisTemplate.opsForHash()
                    .entries(DataConstants.COURSE_AMOUNT_KEY_PREFIX + day);
            Map<Object, Object> refunds = redisTemplate.opsForHash()
                    .entries(DataConstants.COURSE_REFUND_KEY_PREFIX + day);
            int rank = 1;
            for (ZSetOperations.TypedTuple<String> t : tuples) {
                if (t.getValue() == null) {
                    continue;
                }
                String cid = t.getValue();
                list.add(new CourseTopVO()
                        .setRank(rank++)
                        .setCourseId(Long.valueOf(cid))
                        .setOrderCount(t.getScore() == null ? 0 : (int) Math.round(t.getScore()))
                        .setOrderAmount(longOf(amounts, cid))
                        .setRefundCount((int) longOf(refunds, cid))
                        .setSource("realtime"));
            }
            return list;
        }

        // Redis 没有（已落库被清理）→ 查 MySQL
        List<DataCourseDaily> rows = courseDailyMapper.listTopByDate(statDate, n);
        int rank = 1;
        for (DataCourseDaily r : rows) {
            list.add(new CourseTopVO()
                    .setRank(rank++)
                    .setCourseId(r.getCourseId())
                    .setOrderCount(r.getOrderCount())
                    .setOrderAmount(r.getOrderAmount())
                    .setRefundCount(r.getRefundCount())
                    .setSource("archived"));
        }
        return list;
    }

    @Override
    public TrendVO trend(int days) {
        if (days < 1 || days > DataConstants.MAX_TREND_DAYS) {
            throw new BadRequestException(DataErrorInfo.Msg.TREND_DAYS_ILLEGAL);
        }
        LocalDate start = LocalDate.now().minusDays(days - 1L);
        List<DataDaily> rows = dailyMapper.listTrend(Integer.parseInt(start.format(DAY_FMT)), days);

        TrendVO vo = new TrendVO();
        for (DataDaily d : rows) {
            vo.getDates().add(d.getStatDate());
            vo.getOrderCounts().add(nullToZero(d.getOrderCount()));
            vo.getOrderAmounts().add(d.getOrderAmount() == null ? 0L : d.getOrderAmount());
            vo.getSignInCounts().add(nullToZero(d.getSignInCount()));
            vo.getLearnCounts().add(nullToZero(d.getLearnCount()));
            vo.getAssessCounts().add(nullToZero(d.getAssessCount()));
        }
        // ★ 不补零：没数据的那天就不出现在图里。
        //   补成 0 会让"有一天没统计到"看起来像"那天真的为零"——
        //   这和"统计错误不能静默返回 0"是同一个原则
        if (vo.getDates().isEmpty()) {
            log.info("趋势查询无数据：最近 {} 天没有已落库的日数据", days);
        }
        return vo;
    }

    @Override
    public int flush(LocalDate date) {
        return flushService.flushDaily(date);
    }

    // ==================== 工具 ====================
    private OverviewVO toVO(DataDaily d, String source) {
        return new OverviewVO()
                .setStatDate(d.getStatDate())
                .setSignInCount(nullToZero(d.getSignInCount()))
                .setLearnCount(nullToZero(d.getLearnCount()))
                .setPracticeCount(nullToZero(d.getPracticeCount()))
                .setOrderCount(nullToZero(d.getOrderCount()))
                .setOrderAmount(d.getOrderAmount() == null ? 0L : d.getOrderAmount())
                .setRefundCount(nullToZero(d.getRefundCount()))
                .setRefundAmount(d.getRefundAmount() == null ? 0L : d.getRefundAmount())
                .setAssessCount(nullToZero(d.getAssessCount()))
                .setSource(source);
    }

    private int nullToZero(Integer v) {
        return v == null ? 0 : v;
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
            return 0L;
        }
    }
}
