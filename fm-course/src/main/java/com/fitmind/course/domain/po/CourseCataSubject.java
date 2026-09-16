package com.fitmind.course.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 训练节点 ↔ 题目 关联（正式表）。
 *
 * <p>{@code cataId} 指向 {@code course_catalogue.id}，既可以是「训练动作」（type=2），
 * 也可以是「测评」节点（type=3）——所以动作下可以挂小测，训练日末尾也能来一次综合测评。</p>
 *
 * <p>⚠️ <b>常见实现的这张正式表从来没被写过</b>：它上架时把关系跨服务写进 测评服务 的
 * {@code question_biz}，正式表是死表。我们不建独立考试服务，保留这张正式表，
 * 与目录/教练的「草稿-发布」机制完全同构。</p>
 */
@Data
@Accessors(chain = true)
@TableName("course_cata_subject")
public class CourseCataSubject implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键（雪花id，与目录表一致） */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long courseId;

    /** 目录节点id（训练动作 / 测评） */
    private Long cataId;

    private Long subjectId;

    /** 题目在该节点下的顺序（第几题） */
    private Integer cIndex;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
