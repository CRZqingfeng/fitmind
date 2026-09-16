package com.fitmind.data.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.fitmind.data.domain.po.DataCourseDaily;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DataCourseDailyMapper extends BaseMapper<DataCourseDaily> {

    /**
     * 某天的销量榜 TopN。
     *
     * <p>走 {@code idx_date_sales(stat_date, order_count)} 索引，
     * 不需要在内存里排序。</p>
     */
    @Select("""
            SELECT * FROM data_course_daily
            WHERE stat_date = #{statDate} AND order_count > 0
            ORDER BY order_count DESC, order_amount DESC
            LIMIT #{limit}
            """)
    List<DataCourseDaily> listTopByDate(@Param("statDate") Integer statDate, @Param("limit") int limit);

    @Select("""
            SELECT * FROM data_course_daily
            WHERE stat_date = #{statDate} AND course_id = #{courseId}
            """)
    DataCourseDaily getByDateAndCourse(@Param("statDate") Integer statDate, @Param("courseId") Long courseId);
}
