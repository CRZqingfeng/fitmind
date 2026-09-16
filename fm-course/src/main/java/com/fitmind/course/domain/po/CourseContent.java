package com.fitmind.course.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 课程正文（正式表）。
 *
 * <p>与 {@code course} 一对一，<b>共用同一个 id</b>（id 就是课程 id），
 * 所以不需要额外的 courseId 字段。</p>
 */
@Data
@Accessors(chain = true)
@TableName("course_content")
public class CourseContent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 课程 id，与 course.id 一致 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 课程介绍 */
    private String courseIntroduce;

    /** 适合人群 */
    private String usePeople;

    /** 课程详情 */
    private String courseDetail;

    /** ★ 训练注意事项（伤病提醒，健身场景新增） */
    private String precautions;

    /** ★ 所需器械清单（健身场景新增） */
    private String requiredEquipment;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long creater;

    private Long updater;

    private Integer deleted;
}
