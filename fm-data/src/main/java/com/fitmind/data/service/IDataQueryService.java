package com.fitmind.data.service;

import com.fitmind.data.domain.vo.CourseTopVO;
import com.fitmind.data.domain.vo.OverviewVO;
import com.fitmind.data.domain.vo.TrendVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 数据查询服务（管理端看板）。
 */
public interface IDataQueryService {

    /**
     * 某天的运营概览。
     *
     * <p>★ 今天的数据从 <b>Redis</b> 读（还没落库），历史数据从 <b>MySQL</b> 读。
     * 这个"双来源"是本模块唯一有点绕的地方，VO 里用 {@code source} 字段标出来。</p>
     *
     * @param date 日期；null 表示今天
     */
    OverviewVO overview(LocalDate date);

    /**
     * 某天的销量榜 TopN。
     *
     * @param date 日期；null 表示今天
     * @param limit 取前几名
     */
    List<CourseTopVO> courseTop(LocalDate date, int limit);

    /**
     * 趋势数据（最近 N 天，含今天）。
     *
     * @param days 天数，1~90
     */
    TrendVO trend(int days);

    /**
     * 手动触发一次落库（测试 / 补数用）。
     *
     * @param date 日期；null 表示昨天
     */
    int flush(LocalDate date);
}
