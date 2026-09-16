package com.fitmind.learning.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 互动回答与评论（楼中楼）。
 *
 * <p>⚠️ 顶层回答的 {@code answerId} 固定存 <b>0</b> 而不是 null：
 * 分页查询用 {@code eq(answerId, isQueryAnswer ? 0L : answerId)} 一把梭，
 * 避免 IS NULL 与 = 0 两套写法混用导致漏查。</p>
 */
@Data
@Accessors(chain = true)
@TableName("interaction_reply")
public class InteractionReply implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long questionId;

    /** 上级回答id；顶层回答为 0 */
    private Long answerId;

    /** 回答者id */
    private Long userId;

    private String content;

    /** 回复的目标用户id（楼中楼） */
    private Long targetUserId;

    /** 回复的目标回复id（楼中楼） */
    private Long targetReplyId;

    /** 下属评论数量（原子累加） */
    private Integer replyTimes;

    /** 点赞数（由 fm-remark 异步同步，覆盖更新） */
    private Integer likedTimes;

    /** 是否被隐藏（管理端） */
    private Boolean hidden;

    /** 是否匿名 */
    private Boolean anonymity;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
