package com.fitmind.learning.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 学习课表（一人一课一行，唯一索引 uk_user_course）。
 *
 * <p>报名即写入；重复报名被唯一索引挡住（幂等的最后一道防线）。</p>
 */
@Data
@Accessors(chain = true)
@TableName("learning_lesson")
public class LearningLesson implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long courseId;

    /** 课程状态：0-未学习 1-学习中 2-已学完 3-已过期 */
    private Integer status;

    /** 每周训练频率（学习计划），null=未设置 */
    private Integer weekFreq;

    /** 计划状态：0-没有计划 1-计划进行中 */
    private Integer planStatus;

    /** 已完成的训练动作数（原子累加） */
    private Integer learnedSections;

    /** 最近学习的动作id（断点续播入口） */
    private Long latestSectionId;

    /** 最近学习时间（「继续训练」排序用） */
    private LocalDateTime latestLearnTime;

    private LocalDateTime createTime;

    /** 到期时间 = 报名时间 + 课程 validDuration 天；null=永不过期 */
    private LocalDateTime expireTime;

    private LocalDateTime updateTime;
}
