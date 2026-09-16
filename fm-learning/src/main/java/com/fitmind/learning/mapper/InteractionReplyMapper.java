package com.fitmind.learning.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.learning.domain.po.InteractionReply;
import org.apache.ibatis.annotations.Mapper;

/**
 * 互动回答 Mapper。
 */
@Mapper
public interface InteractionReplyMapper extends BaseMapper<InteractionReply> {
}
