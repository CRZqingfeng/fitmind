package com.fitmind.learning.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 互动提问。
 */
@Data
@Accessors(chain = true)
@TableName("interaction_question")
public class InteractionQuestion implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String title;

    private String description;

    private Long courseId;

    /** 训练日id（目录一级） */
    private Long chapterId;

    /** 训练动作id（目录二级） */
    private Long sectionId;

    /** 提问学员id */
    private Long userId;

    /**
     * 最新一条回答的 id。
     *
     * <p>⚠️ 常见实现在这个字段上有个笔误（把 {@code reply.getAnswerId()} 写了进去，
     * 而它在"新增回答"时必然为 null），导致问题列表的"最新回答"永远是空的。
     * 我们用 {@code reply.getId()}。</p>
     */
    private Long latestAnswerId;

    /** 回答数量（原子累加） */
    private Integer answerTimes;

    /** 是否匿名 */
    private Boolean anonymity;

    /** 是否被隐藏（管理端） */
    private Boolean hidden;

    /** 管理端状态：0-未查看 1-已查看 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
