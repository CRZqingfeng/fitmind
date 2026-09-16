package com.fitmind.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.course.domain.po.CourseTeacher;
import org.apache.ibatis.annotations.Mapper;

/**
 * 课程教练（正式表）Mapper。
 */
@Mapper
public interface CourseTeacherMapper extends BaseMapper<CourseTeacher> {
}
