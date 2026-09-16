package com.fitmind.aigc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.aigc.domain.po.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

/** 消息 Mapper（{@code @Mapper} 说明见 {@link ChatSessionMapper}） */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
