package com.fitmind.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.learning.domain.po.LearningLesson;
import org.apache.ibatis.annotations.Mapper;

/**
 * 学习课表 Mapper。
 */
@Mapper
public interface LearningLessonMapper extends BaseMapper<LearningLesson> {
}
