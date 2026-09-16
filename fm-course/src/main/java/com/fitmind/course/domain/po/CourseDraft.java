package com.fitmind.course.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 课程基本信息（草稿表）。
 *
 * <p>教练的所有编辑都发生在这里，点「上架」才整体同步到 {@link Course}。
 * 上架后本表记录删除；下架时再从正式表反向复制回来，教练接着改。</p>
 *
 * <p>{@code step} 记录填写进度，支持<b>断点续填</b>：
 * 教练今天填到目录就关页面，明天回来接着填。</p>
 */
@Data
@Accessors(chain = true)
@TableName("course_draft")
public class CourseDraft implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 草稿 id，上架后成为 course.id */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    /** 课程类型：1-训练计划课 2-单动作教学 3-理论饮食课 */
    private Integer courseType;

    private String coverUrl;

    private Long firstCateId;

    private Long secondCateId;

    private Long thirdCateId;

    /** 是否免费：1-免费 2-付费 */
    private Integer free;

    /** 价格，单位分 */
    private Integer price;

    /** 状态：1-待上架 2-已上架 3-下架 4-已完结 */
    private Integer status;

    /** 填写进度 */
    private Integer step;

    private Integer score;

    private Integer mediaDuration;

    private Integer validDuration;

    private Integer sectionNum;

    private Integer goalType;

    private Integer trainingLevel;

    private Integer totalDays;

    private Integer weeklyFrequency;

    private String equipment;

    private Integer estimatedCalories;

    private LocalDateTime enrollStartTime;

    private LocalDateTime enrollEndTime;

    /** 是否可修改：1-可修改 0-锁定（上架过程中短暂锁定，防止并发编辑） */
    private Boolean canUpdate;

    /** 乐观锁版本号 */
    private Integer cVersion;

    private LocalDateTime publishTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long creater;

    private Long updater;
}
