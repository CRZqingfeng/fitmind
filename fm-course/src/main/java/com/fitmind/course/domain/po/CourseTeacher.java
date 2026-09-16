package com.fitmind.course.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 课程教练（正式表）。
 *
 * <p>{@code teacherId} 关联 fm-user 的教练账号（{@code UserType=3}）。</p>
 */
@Data
@Accessors(chain = true)
@TableName("course_teacher")
public class CourseTeacher implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 课程 id */
    private Long courseId;

    /** 教练用户 id */
    private Long teacherId;

    /** 是否展示：1-展示 0-隐藏 */
    private Integer isShow;

    /** 排序序号 */
    private Integer cIndex;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long creater;

    private Long updater;

    private Integer deleted;
}
