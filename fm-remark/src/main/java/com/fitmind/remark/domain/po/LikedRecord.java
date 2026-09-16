package com.fitmind.remark.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 点赞明细（永久凭证）。
 *
 * <p>常见实现的 Redis 版实现<b>完全不写这张表</b>（Redis 是唯一数据源），风险很实在：
 * Redis 一挂点赞记录就蒸发，而且 {@code SCARD} 归零后同步任务会把业务表的
 * {@code liked_times} 写成 0 —— 那是数据损坏，不是"丢点赞"。</p>
 *
 * <p>我们的分工：Redis 管热（判重 + 计数，快），这张表管冷（可重建、可对账）。</p>
 *
 * <p>{@code uk_user_biz} 唯一索引是落库幂等的最后一道防线：
 * 若 Redis 说"首次点赞"但 DB 已有行，说明两边不一致，会打 WARN 日志作为探针。</p>
 */
@Data
@Accessors(chain = true)
@TableName("liked_record")
public class LikedRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 点赞人id */
    private Long userId;

    /** 业务id（回答id / 笔记id） */
    private Long bizId;

    /** 业务类型：QA-问答回答 NOTE-笔记 */
    private String bizType;

    private LocalDateTime createTime;
}
