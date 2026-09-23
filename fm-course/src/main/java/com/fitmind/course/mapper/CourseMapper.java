package com.fitmind.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.course.domain.po.Course;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 课程（正式表）Mapper。
 */
@Mapper
public interface CourseMapper extends BaseMapper<Course> {

    /**
     * 统计同名课程数量（上架时做重名校验）。
     */
    @Select("SELECT COUNT(*) FROM course WHERE name = #{name} AND deleted = 0")
    int countSameName(@Param("name") String name);

    /**
     * 报名人数增量更新（M7-3）。
     *
     * <p>★ 为什么必须是 {@code sold = sold + delta} 这条 SQL，而不是
     * "查出来 +delta 再 updateById"：后者是典型的<b>读-改-写</b>，
     * 两个订单同时支付同一门课时会丢更新（都读到 0，都写 1，实际应该是 2）。
     * 报名人数恰恰是最容易被并发撞上的字段。</p>
     *
     * <p>★ {@code GREATEST(..., 0)} 兜底：退款消息若因重投/乱序先于支付消息到达，
     * {@code sold - 1} 会算出负数。负的报名人数比"少算一个"更难解释。</p>
     *
     * @param courseId 课程 id
     * @param delta    变动值（+1 报名 / -1 退款）
     * @return 影响行数（0 表示课程不存在或已被逻辑删除）
     */
    @Update("UPDATE course SET sold = GREATEST(COALESCE(sold, 0) + #{delta}, 0), update_time = NOW() "
            + "WHERE id = #{courseId} AND deleted = 0")
    int increaseSold(@Param("courseId") Long courseId, @Param("delta") int delta);
}
