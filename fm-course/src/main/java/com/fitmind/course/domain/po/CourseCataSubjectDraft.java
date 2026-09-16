package com.fitmind.course.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 训练节点 ↔ 题目 关联（草稿表）。
 *
 * <p>字段与正式表完全对称。同一时刻数据只在一张表里：
 * 上架时草稿 → 正式，下架时正式 → 草稿。</p>
 */
@Data
@Accessors(chain = true)
@TableName("course_cata_subject_draft")
public class CourseCataSubjectDraft implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long courseId;

    private Long cataId;

    private Long subjectId;

    private Integer cIndex;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
