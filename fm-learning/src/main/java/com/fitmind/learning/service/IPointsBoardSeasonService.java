package com.fitmind.learning.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.learning.domain.po.PointsBoardSeason;
import com.fitmind.learning.domain.vo.PointsBoardSeasonVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 积分赛季服务。
 */
public interface IPointsBoardSeasonService extends IService<PointsBoardSeason> {

    /**
     * 按时间查所属赛季 id。
     *
     * @param date 日期
     * @return 赛季 id；无匹配赛季返回 null
     */
    Integer querySeasonByTime(LocalDate date);

    /**
     * 确保某个自然月的赛季存在（存在则返回，不存在则创建）。
     *
     * <p>常见实现的赛季表靠人工插数据，没插数据整个榜单结算会静默不工作；
     * 这里让赛季自己滚动，系统自转。</p>
     *
     * @param month 该月的任意一天
     * @return 赛季 id
     */
    Integer ensureSeason(LocalDate month);

    /** 查询已开始的赛季列表（按开始时间倒序） */
    List<PointsBoardSeasonVO> querySeasonList();
}
