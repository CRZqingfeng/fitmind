package com.fitmind.aigc.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * AI 会话消息（提问与回答同一张表，靠 {@code role} 区分）。
 *
 * <p>★ 为什么不用"一问一答一行"的宽表：多轮对话里<b>轮次边界会因为"打断重答"而不清晰</b>
 * —— 用户点了停止、再问一次，宽表就很难表达"这条回答属于哪次提问"。
 * 单条消息一行的代价是多一次关联，收益是**每一条消息都能独立携带自己的元数据**
 * （token 数、首字延迟、工具调用记录），这在做可观测时非常值。</p>
 *
 * <p>⚠️ {@code autoResultMap = true} 是必须的：没有它，
 * {@code JacksonTypeHandler} 只在<b>写</b>的时候生效，读出来是原始的 JSON 字符串。</p>
 */
@Data
@Accessors(chain = true)
@TableName(value = "chat_message", autoResultMap = true)
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    /** 冗余存一份：查消息时不用回表 join 会话（消息表永远是按会话查的，这列省掉一次连接） */
    private Long userId;

    /**
     * 本轮请求id。
     *
     * <p>一次提问 = 一个 requestId，它的提问消息与回答消息共用这个 id。
     * 打断 / 重试 / SSE 重连时靠它把"这一轮"圈出来。</p>
     */
    private String requestId;

    /** {@code user} 或 {@code assistant} */
    private String role;

    private String content;

    /** 本条回答由哪个 Agent 产出（M6-2 起有值） */
    private String agentType;

    /** 模型通道：deepseek / mock —— 出问题复盘时第一眼要看的信息 */
    private String provider;

    // ==================== 可观测（都写在助手消息上） ====================

    private Integer promptTokens;

    private Integer completionTokens;

    /** 首字延迟：流式体验的核心指标，和"总耗时"是两个不同的问题 */
    private Integer firstTokenMs;

    private Integer totalMs;

    /** 本轮调用过的工具（名称/参数/结果摘要），JSON 列 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object toolCalls;

    /** 卡片数据，JSON 列。★ 前端翻历史时靠它重渲染工具卡片 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object params;

    private LocalDateTime createTime;
}
