package com.fitmind.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.learning.domain.po.InteractionQuestion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 互动提问 Mapper。
 */
@Mapper
public interface InteractionQuestionMapper extends BaseMapper<InteractionQuestion> {
}
