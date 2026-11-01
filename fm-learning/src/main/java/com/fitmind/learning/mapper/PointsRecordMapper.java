package com.fitmind.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.learning.domain.po.PointsBoard;
import com.fitmind.learning.domain.po.PointsRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 积分明细 Mapper。
 */
@Mapper
public interface PointsRecordMapper extends BaseMapper<PointsRecord> {

    /**
     * 按用户汇总某段时间内的积分（已按分数从高到低排好）。
     *
     * <p>用途：<b>历史赛季的跨月兜底</b>。历史赛季正常情况下读的是月度结算分表
     * （{@code points_board_{season}}），但那张表依赖 XXL-JOB 结算任务跑过；
     * 任务没跑成时表根本不存在，历史赛季就会永久空白（#C42）。
     * 这个查询让查询侧能直接用流水把任意月份的榜重算出来。</p>
     *
     * <p>★ 返回类型复用 {@link PointsBoard}：它就是「userId + points + rank」的形状，
     * 与 Redis 实时榜那条路（{@code listFromRedis}）构造出来的是同一个对象。
     * 注意它带的 {@code @TableName("points_board")} 只对 MyBatis-Plus 的自动 SQL 生效，
     * 这里是手写 SQL、查的是 {@code points_record}，不会被动态表名拦截器改写。</p>
     *
     * <p>★ 排序里带 {@code user_id} 兜底：分数相同时给一个<b>确定</b>的顺序，
     * 否则同一份数据两次查询可能给出不同的名次（分页会跟着抖）。</p>
     *
     * @param begin 起始时间（含）
     * @param end   结束时间（不含）
     * @return 每个用户一行，{@code rank} 字段未填（由调用方按顺序赋名次）
     */
    @Select("""
            SELECT user_id AS userId, SUM(points) AS points
            FROM points_record
            WHERE create_time >= #{begin} AND create_time < #{end}
            GROUP BY user_id
            ORDER BY SUM(points) DESC, user_id ASC
            """)
    List<PointsBoard> sumPointsGroupByUser(@Param("begin") LocalDateTime begin,
                                           @Param("end") LocalDateTime end);
}
