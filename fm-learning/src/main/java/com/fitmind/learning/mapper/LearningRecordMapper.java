package com.fitmind.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.learning.domain.po.LearningRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 学习记录 Mapper。
 */
@Mapper
public interface LearningRecordMapper extends BaseMapper<LearningRecord> {
}
