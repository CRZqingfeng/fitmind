package com.fitmind.learning.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.learning.domain.po.PointsBoardSeason;
import com.fitmind.learning.domain.vo.PointsBoardSeasonVO;
import com.fitmind.learning.mapper.PointsBoardSeasonMapper;
import com.fitmind.learning.service.IPointsBoardSeasonService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 积分赛季实现。
 */
@Slf4j
@Service
public class PointsBoardSeasonServiceImpl extends ServiceImpl<PointsBoardSeasonMapper, PointsBoardSeason>
        implements IPointsBoardSeasonService {

    private static final DateTimeFormatter MONTH_NAME = DateTimeFormatter.ofPattern("yyyy年MM月");

    @Override
    public Integer querySeasonByTime(LocalDate date) {
        PointsBoardSeason season = lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, date)
                .ge(PointsBoardSeason::getEndTime, date)
                .last("limit 1")
                .one();
        return season == null ? null : season.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer ensureSeason(LocalDate month) {
        LocalDate firstDay = month.withDayOfMonth(1);
        Integer exist = querySeasonByTime(firstDay);
        if (exist != null) {
            return exist;
        }
        PointsBoardSeason season = new PointsBoardSeason()
                .setName(firstDay.format(MONTH_NAME) + "赛季")
                .setBeginTime(firstDay)
                .setEndTime(firstDay.withDayOfMonth(firstDay.lengthOfMonth()));
        save(season);
        log.info("已创建赛季：{}（id={}）", season.getName(), season.getId());
        return season.getId();
    }

    @Override
    public List<PointsBoardSeasonVO> querySeasonList() {
        // ★★ 只返回「已结束」的赛季（#C41）。
        //
        // ⚠️ 不要写成 le(beginTime, now) —— 那会把**正在进行**的赛季也列出来，而它的分表
        //    要到下月 1 号结算时才建（PointsBoardJob#createTableJob），
        //    于是下拉里出现一个**点开必然为空**的选项：
        //      「当前赛季（实时）」= 本月（Redis 实时榜）
        //      「2026年10月赛季」  = 同一个月的分表入口 —— 但分表现在还不存在
        //    用户看到的就是"两个一样的选项，其中一个永远没数据"。
        //
        // ★ 当前赛季不由这个接口代表：前端固定有「当前赛季（实时）」这一项，
        //   走 Redis 实时榜（数据源与分表完全不同）。
        //
        // ★ 顺带：`lt(endTime, today)` 也天然排除了「未开始」的赛季
        //   （下学期 season 在每月 25 日就建好了，begin_time 在未来，end_time 必然也在未来）。
        return lambdaQuery()
                .lt(PointsBoardSeason::getEndTime, LocalDate.now())
                .orderByDesc(PointsBoardSeason::getBeginTime)
                .list()
                .stream()
                .map(s -> BeanUtil.copyProperties(s, PointsBoardSeasonVO.class))
                .toList();
    }
}
