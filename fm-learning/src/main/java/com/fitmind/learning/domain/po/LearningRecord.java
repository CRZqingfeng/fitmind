package com.fitmind.learning.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 学习记录（动作级进度，一人一动作一行）。
 *
 * <p>高频上报由 {@code LearningRecordDelayTaskHandler} 合并落库；
 * 这张表里只存"断点值"（moment）和"完成态"（finished/finishTime）。</p>
 */
@Data
@Accessors(chain = true)
@TableName("learning_record")
public class LearningRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long lessonId;

    /** 训练动作id（fm_course.course_catalogue.id） */
    private Long sectionId;

    private Long userId;

    /** 上次播放进度，单位秒（断点） */
    private Integer moment;

    /** 是否完成：0-否 1-是 */
    private Integer finished;

    private LocalDateTime createTime;

    /** 完成时间（M3-2 周统计的依据） */
    private LocalDateTime finishTime;

    private LocalDateTime updateTime;
}
