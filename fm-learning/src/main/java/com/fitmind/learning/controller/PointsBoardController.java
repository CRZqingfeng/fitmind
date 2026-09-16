package com.fitmind.learning.controller;

import com.fitmind.learning.domain.query.PointsBoardQuery;
import com.fitmind.learning.domain.vo.PointsBoardSeasonVO;
import com.fitmind.learning.domain.vo.PointsBoardVO;
import com.fitmind.learning.domain.vo.PointsStatisticsVO;
import com.fitmind.learning.service.IPointsBoardSeasonService;
import com.fitmind.learning.service.IPointsBoardService;
import com.fitmind.learning.service.IPointsRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 积分与榜单接口。
 */
@RestController
@RequestMapping("/boards")
@RequiredArgsConstructor
@Tag(name = "积分与榜单相关接口")
public class PointsBoardController {

    private final IPointsBoardService pointsBoardService;
    private final IPointsBoardSeasonService seasonService;
    private final IPointsRecordService pointsRecordService;

    @Operation(summary = "积分榜（season 为空查当前赛季走 Redis，否则查历史赛季分表）")
    @GetMapping
    public PointsBoardVO queryBoard(PointsBoardQuery query) {
        return pointsBoardService.queryBoard(query);
    }

    @Operation(summary = "赛季列表（只返回已开始的赛季）")
    @GetMapping("/seasons/list")
    public List<PointsBoardSeasonVO> querySeasons() {
        return seasonService.querySeasonList();
    }

    @Operation(summary = "今日积分明细（按类型汇总，含每日上限）")
    @GetMapping("/points/today")
    public List<PointsStatisticsVO> queryMyPointsToday() {
        return pointsRecordService.queryMyPointsToday();
    }

    // ==================== 内部接口（XXL-JOB 任务的手动兜底，验收也用它） ====================

    /**
     * 【内部】建指定赛季的分表。
     *
     * <p>与任务的区别：这里<b>显式传 season</b>，而任务里写死了"上个月"——
     * 常见实现写死时间，验收只能等到月初或改系统时间，可测试性差。</p>
     */
    @Operation(summary = "【内部】建赛季分表", hidden = true)
    @GetMapping("/internal/create-table")
    public String createTable(
            @Parameter(description = "赛季id") @RequestParam("season") Integer season) {
        pointsBoardService.createTable(season);
        return "ok";
    }

    /** 【内部】把赛季榜落到分表（单实例等价于 shardIndex=0, shardTotal=1） */
    @Operation(summary = "【内部】赛季榜落库", hidden = true)
    @GetMapping("/internal/save-to-db")
    public int saveToDb(
            @Parameter(description = "赛季id") @RequestParam("season") Integer season,
            @Parameter(description = "分片序号") @RequestParam(value = "shardIndex", defaultValue = "0") int shardIndex,
            @Parameter(description = "分片总数") @RequestParam(value = "shardTotal", defaultValue = "1") int shardTotal) {
        return pointsBoardService.saveToDb(season, shardIndex, shardTotal);
    }

    /** 【内部】清理赛季榜 Redis */
    @Operation(summary = "【内部】清理赛季榜 Redis", hidden = true)
    @GetMapping("/internal/clear-redis")
    public String clearRedis(
            @Parameter(description = "赛季id") @RequestParam("season") Integer season) {
        pointsBoardService.clearRedis(season);
        return "ok";
    }

    /**
     * 【内部】确保某月赛季存在（验收用：可指定月份，不依赖系统时间）。
     */
    @Operation(summary = "【内部】确保月份赛季存在", hidden = true)
    @GetMapping("/internal/ensure-season")
    public Integer ensureSeason(
            @Parameter(description = "月份，格式 yyyy-MM，缺省为下月")
            @RequestParam(value = "month", required = false) String month) {
        LocalDate target = (month == null || month.isBlank())
                ? LocalDate.now().plusMonths(1)
                : LocalDate.parse(month + "-01");
        return seasonService.ensureSeason(target);
    }
}
