package com.fitmind.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.course.domain.po.Course;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
