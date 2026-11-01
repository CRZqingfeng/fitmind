package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.WorkoutSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * 训练 Mapper。
 *
 * <p>分钟数在 SQL 里 SUM，而不是"查出来在 Java 里加"：
 * 累计训练可能是几十上百条 session，全查回来只为求和，是把数据库当文件系统用。</p>
 */
@Mapper
public interface WorkoutSessionMapper extends BaseMapper<WorkoutSession> {

    /** 某天已完成训练的总秒数（status=1 才算，练到一半退出不计） */
    @Select("SELECT COALESCE(SUM(duration_sec), 0) FROM workout_session "
            + "WHERE user_id = #{userId} AND session_date = #{date} AND status = 1")
    long sumDurationOfDate(@Param("userId") Long userId, @Param("date") LocalDate date);

    /** 累计已完成训练的总秒数 */
    @Select("SELECT COALESCE(SUM(duration_sec), 0) FROM workout_session "
            + "WHERE user_id = #{userId} AND status = 1")
    long sumDurationAll(@Param("userId") Long userId);
}
