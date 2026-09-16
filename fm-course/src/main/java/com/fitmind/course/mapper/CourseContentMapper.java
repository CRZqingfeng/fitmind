package com.fitmind.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.course.domain.po.CourseContent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 课程正文（正式表）Mapper。
 */
@Mapper
public interface CourseContentMapper extends BaseMapper<CourseContent> {
}
