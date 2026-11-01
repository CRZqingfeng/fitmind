package com.fitmind.data.controller;

import com.fitmind.data.domain.vo.CourseTopVO;
import com.fitmind.data.domain.vo.OverviewVO;
import com.fitmind.data.domain.vo.TrendVO;
import com.fitmind.data.service.IDataQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 数据中心（管理端）。
 *
 * <p>三个接口对应运营看板的三块：<b>今天怎么样 / 哪门课卖得好 / 最近走势</b>。</p>
 *
 * <p>★ 全部走 {@code /admin/**} 前缀，由 {@code AdminAuthInterceptor}
 * 校验管理员身份 —— 运营数据是内部信息，学员不该看到平台成交额。</p>
 */
@Tag(name = "数据中心（管理端）")
@RestController
@RequestMapping("/admin/data")
@RequiredArgsConstructor
public class  DataAdminController {

    private final IDataQueryService queryService;

    @Operation(summary = "运营概览（今日 / 指定日期）")
    @GetMapping("/overview")
    public OverviewVO overview(
            @Parameter(description = "日期 yyyyMMdd，不传=今天")
            @RequestParam(value = "date", required = false) Integer date) {
        return queryService.overview(parseDate(date));
    }

    @Operation(summary = "课程销量榜 TopN")
    @GetMapping("/course-top")
    public List<CourseTopVO> courseTop(
            @Parameter(description = "日期 yyyyMMdd，不传=今天")
            @RequestParam(value = "date", required = false) Integer date,
            @Parameter(description = "取前几名，默认 10")
            @RequestParam(value = "limit", required = false, defaultValue = "10") Integer limit) {
        return queryService.courseTop(parseDate(date), limit == null ? 10 : limit);
    }

    @Operation(summary = "趋势数据（最近 N 天）")
    @GetMapping("/trend")
    public TrendVO trend(
            @Parameter(description = "天数，1~90，默认 7")
            @RequestParam(value = "days", required = false, defaultValue = "7") Integer days) {
        return queryService.trend(days == null ? 7 : days);
    }

    /**
     * 手动落库（补数 / 验证用）。
     *
     * <p>★ 为什么把这个暴露成接口而不是只留定时任务：
     * 落库任务每天凌晨才跑，验收脚本和人工排查不可能等到凌晨。
     * 它<b>幂等</b>（覆盖式写入），多跑几次不会出错。</p>
     */
    @Operation(summary = "手动落库（幂等，可重复调用）")
    @PostMapping("/flush")
    public Integer flush(
            @Parameter(description = "日期 yyyyMMdd，不传=昨天")
            @RequestParam(value = "date", required = false) Integer date) {
        return queryService.flush(parseDate(date));
    }

    /** yyyyMMdd → LocalDate；参数不合法直接抛 400，不静默当成今天 */
    private LocalDate parseDate(Integer date) {
        if (date == null) {
            return null;
        }
        try {
            return LocalDate.of(date / 10000, date / 100 % 100, date % 100);
        } catch (Exception e) {
            throw new com.fitmind.common.exceptions.BadRequestException(
                    com.fitmind.data.constants.DataErrorInfo.Msg.DATE_ILLEGAL);
        }
    }
}
