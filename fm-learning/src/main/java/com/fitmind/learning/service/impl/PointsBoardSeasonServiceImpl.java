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
        // 只返回已开始的赛季（未到的赛季不该出现在下拉里）
        return lambdaQuery()
                .le(PointsBoardSeason::getBeginTime, LocalDate.now())
                .orderByDesc(PointsBoardSeason::getBeginTime)
                .list()
                .stream()
                .map(s -> BeanUtil.copyProperties(s, PointsBoardSeasonVO.class))
                .toList();
    }
}
