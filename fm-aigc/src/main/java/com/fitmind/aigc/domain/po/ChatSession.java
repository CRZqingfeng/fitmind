package com.fitmind.aigc.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * AI 会话。
 *
 * <p>只存元数据，消息正文在 {@link ChatMessage}。分开的理由是它们的
 * <b>读写形态完全不同</b>：会话列表要按最后活跃时间翻页（读少量字段、要索引），
 * 而消息是"一次写一条、按会话按序读"（大字段、不需要排序索引）。</p>
 */
@Data
@Accessors(chain = true)
@TableName("chat_session")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话id（前端生成或服务端回填） */
    private String sessionId;

    /** 归属用户 —— 所有查询都必须带上它（越权防护做在索引里，见建表注释） */
    private Long userId;

    /** 会话标题：取首轮提问的前若干字（不是让模型生成摘要 —— 那要多花一次调用） */
    private String title;

    /** 消息条数（含提问与回答） */
    private Integer msgCount;

    /** 最后活跃时间，会话列表按它倒序 */
    private LocalDateTime lastActive;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
