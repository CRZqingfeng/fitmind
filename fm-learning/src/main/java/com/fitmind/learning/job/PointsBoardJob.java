package com.fitmind.learning.job;

import com.fitmind.learning.service.IPointsBoardSeasonService;
import com.fitmind.learning.service.IPointsBoardService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 积分榜月度结算任务。
 *
 * <p>四个任务串起"赛季滚动 → 建表 → 落库 → 清缓存"的完整生命周期。
 * 常见实现只有后三个，赛季靠人工插数据（没插则整条链路静默不工作）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointsBoardJob {

    private final IPointsBoardSeasonService seasonService;
    private final IPointsBoardService pointsBoardService;

    /**
     * 赛季自动滚动：每月 25 日确保下月赛季存在。
     */
    @XxlJob("ensureSeasonJob")
    public void ensureSeasonJob() {
        LocalDate nextMonth = LocalDate.now().plusMonths(1);
        Integer season = seasonService.ensureSeason(nextMonth);
        XxlJobHelper.log("下月赛季已就绪：season={}", season);
        log.info("[XXL-JOB] 下月赛季已就绪：season={}", season);
    }

    /**
     * 建上月分表（每月 1 日）。
     */
    @XxlJob("createTableJob")
    public void createTableJob() {
        LocalDate lastMonth = LocalDate.now().minusMonths(1);
        Integer season = seasonService.querySeasonByTime(lastMonth);
        if (season == null) {
            // 赛季不存在就建一个，避免因缺数据导致结算静默跳过
            season = seasonService.ensureSeason(lastMonth);
        }
        pointsBoardService.createTable(season);
        XxlJobHelper.log("上月分表已创建：season={}", season);
    }

    /**
     * ★ 上月榜单落库（每月 1 日，<b>分片广播</b>）。
     *
     * <p>{@code XxlJobHelper.getShardIndex()/getShardTotal()} 只在路由策略为
     * {@code SHARDING_BROADCAST} 时有意义——那时每个执行器实例拿到不同的
     * index/total，各自处理 1/total 的页，并行落库互不重叠。</p>
     */
    @XxlJob("savePointsBoard2DB")
    public void savePointsBoard2DB() {
        LocalDate lastMonth = LocalDate.now().minusMonths(1);
        Integer season = seasonService.querySeasonByTime(lastMonth);
        if (season == null) {
            XxlJobHelper.log("上月无赛季，跳过：{}", lastMonth);
            return;
        }
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        int rows = pointsBoardService.saveToDb(season, shardIndex, shardTotal);
        XxlJobHelper.log("榜单落库完成：season={}, shard={}/{}, 条数={}",
                season, shardIndex, shardTotal, rows);
    }

    /**
     * 清理上月 ZSet（每月 1 日）。
     */
    @XxlJob("clearPointsBoardFromRedis")
    public void clearPointsBoardFromRedis() {
        LocalDate lastMonth = LocalDate.now().minusMonths(1);
        Integer season = seasonService.querySeasonByTime(lastMonth);
        if (season == null) {
            XxlJobHelper.log("上月无赛季，跳过清理：{}", lastMonth);
            return;
        }
        pointsBoardService.clearRedis(season);
        XxlJobHelper.log("上月榜单 Redis 已清理：season={}", season);
    }
}
