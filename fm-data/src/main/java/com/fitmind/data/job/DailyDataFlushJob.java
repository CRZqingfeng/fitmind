package com.fitmind.data.job;

import com.fitmind.data.service.IDataFlushService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 日数据落库任务：把 Redis 里当天的计数写到 MySQL。
 *
 * <p><b>为什么是定时任务而不是直接写库</b>：一条签到消息、一笔成交都要写库的话，
 * MySQL 会被高频小写入打满（而且大部分是"给某个数字 +1"）。
 * Redis 的 {@code HINCRBY} 是内存操作，落库一天一次就够了 ——
 * 运营看板本来就不要求秒级精确。</p>
 *
 * <p>与 M3-2 的积分榜（{@code savePointsBoard2DB}）是同一套思路，
 * 差别只是周期：榜单赛季末落一次，运营数据每天落一次。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyDataFlushJob {

    private final IDataFlushService flushService;

    /**
     * 每天凌晨落<b>昨天</b>的数据。
     *
     * <p>★ 落"昨天"而不是"今天"：今天的计数还在涨，
     * 落下去的数字马上就过时了；而且今晚 23:59 的成交算今天还是明天，
     * 落昨天的话边界是清楚的。</p>
     */
    @XxlJob("dailyDataFlushJob")
    public void flushYesterday() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        try {
            int n = flushService.flushDaily(yesterday);
            if (n > 0) {
                flushService.clearCounter(yesterday);
                log.info("日数据落库完成并已清理计数：{}", yesterday);
            }
        } catch (Exception e) {
            log.error("日数据落库失败：{}", yesterday, e);
            throw e;
        }
    }

    /**
     * 补跑某天（人工介入用）。
     *
     * <p>参数格式 {@code yyyyMMdd}，为空则落昨天。
     * 幂等，可以重复跑。</p>
     */
    @XxlJob("dailyDataFlushManualJob")
    public void flushManual() {
        String param = com.xxl.job.core.context.XxlJobHelper.getJobParam();
        LocalDate date;
        if (param == null || param.isBlank()) {
            date = LocalDate.now().minusDays(1);
        } else {
            try {
                date = LocalDate.of(Integer.parseInt(param.substring(0, 4)),
                        Integer.parseInt(param.substring(4, 6)),
                        Integer.parseInt(param.substring(6, 8)));
            } catch (Exception e) {
                com.xxl.job.core.context.XxlJobHelper.log("日期参数不合法（应为 yyyyMMdd）：{}", param);
                com.xxl.job.core.context.XxlJobHelper.handleFail("日期参数不合法");
                return;
            }
        }
        flushService.flushDaily(date);
        com.xxl.job.core.context.XxlJobHelper.log("补跑完成：{}", date);
    }
}
