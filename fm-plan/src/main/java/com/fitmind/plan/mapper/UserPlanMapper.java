package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.UserPlan;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/**
 * 我执行的计划 Mapper。
 */
@Mapper
public interface UserPlanMapper extends BaseMapper<UserPlan> {

    /**
     * 执行本计划（幂等）：有则重置 start_date，无则插入。
     *
     * <p><b>为什么必须是一条 SQL</b>：写成「先 select，没有就 insert」的话，
     * 用户连点三次「执行本计划」，三个请求可能同时走到 insert —— 前两个还没提交，
     * 第三个查不到就再插一次，于是 {@code user_plan} 出现两条。
     * 唯一索引 {@code uk_user_plan(user_id, plan_id)} 会挡住第三条，但服务会抛
     * DuplicateKeyException（500），用户看到的是"执行失败"。</p>
     *
     * <p><b>为什么用 {@code AS new} 而不是 VALUES()</b>：MySQL 8.0.20 起
     * {@code VALUES(col)} 被标记废弃，会在日志里刷 deprecated 警告。
     * 本项目 MySQL 8.0.41，用行别名是官方推荐写法。</p>
     *
     * <p>重置范围只有 start_date / status / current_day_index —— <b>不动 workout_session</b>：
     * 重新执行是"再来一轮"，历史训练记录必须留着（否则累计训练分钟会莫名减少）。</p>
     */
    @Insert("INSERT INTO user_plan (user_id, plan_id, start_date, status, current_day_index) "
            + "VALUES (#{userId}, #{planId}, #{startDate}, 0, 1) AS new "
            + "ON DUPLICATE KEY UPDATE start_date = new.start_date, status = 0, current_day_index = 1")
    int executePlan(@Param("userId") Long userId,
                    @Param("planId") Long planId,
                    @Param("startDate") LocalDate startDate);
}
