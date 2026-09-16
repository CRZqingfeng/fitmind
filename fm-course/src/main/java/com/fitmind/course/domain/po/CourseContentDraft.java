package com.fitmind.course.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 课程正文（草稿表）。
 *
 * <p>与 {@code course_draft} 共用 id，随课程一起上架 / 下架。</p>
 */
@Data
@Accessors(chain = true)
@TableName("course_content_draft")
public class CourseContentDraft implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 草稿 id，与 course_draft.id 一致 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String courseIntroduce;

    private String usePeople;

    private String courseDetail;

    private String precautions;

    private String requiredEquipment;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long creater;

    private Long updater;
}
