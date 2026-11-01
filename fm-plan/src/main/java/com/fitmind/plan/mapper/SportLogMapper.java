package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.SportLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 运动消耗记录 Mapper。
 */
@Mapper
public interface SportLogMapper extends BaseMapper<SportLog> {

    /**
     * 某天的运动消耗合计。
     *
     * <p>★ 这个数<b>只用于展示</b>（"运动消耗 X 千卡 · 不计入预算"），
     * 不参与"还可以吃"的计算 —— 口径 1 定的是运动消耗不抵扣预算。
     * 抵扣会变成"越练越能吃"，把用户往补偿性进食上引。</p>
     */
    @Select("SELECT COALESCE(SUM(kcal), 0) FROM sport_log WHERE user_id = #{userId} AND log_date = #{date}")
    long sumKcalOfDate(@Param("userId") Long userId, @Param("date") LocalDate date);
}
