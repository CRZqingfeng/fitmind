package com.fitmind.course.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 课程基本信息（正式表）。
 *
 * <p>与 {@link CourseDraft} <b>主键相同</b>——这是「草稿-发布」机制的地基：
 * 上架时带着草稿的 id 直接写入本表，下架时再从本表反向复制回草稿。</p>
 *
 * <p>所以 id 必须由代码指定（INPUT），不能让 MyBatis-Plus 自动生成。</p>
 */
@Data
@Accessors(chain = true)
@TableName("course")
public class Course implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 课程 id，与 course_draft.id 一致 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 课程名称 */
    private String name;

    /** 课程类型：1-训练计划课 2-单动作教学 3-理论饮食课 */
    private Integer courseType;

    /** 封面地址 */
    private String coverUrl;

    /** 一级分类（训练目标） */
    private Long firstCateId;

    /** 二级分类（训练部位） */
    private Long secondCateId;

    /** 三级分类（器械场景） */
    private Long thirdCateId;

    /** 是否免费：1-免费 2-付费 */
    private Integer free;

    /** 价格，单位分 */
    private Integer price;

    /** 状态：1-待上架 2-已上架 3-下架 4-已完结 */
    private Integer status;

    /** 填写进度：1-基本信息 2-目录 3-视频 4-习题 5-教练 */
    private Integer step;

    /** 评分 */
    private Integer score;

    /**
     * 报名人数。
     *
     * <p>冗余计数列，由 {@code CourseSoldListener} 消费 {@code order.pay} /
     * {@code order.refund} 增量维护（不是查询时现算 —— 报名记录在 fm-learning、
     * 订单在 fm-trade，都在别的库）。</p>
     */
    private Integer sold;

    /** 视频总时长，单位秒 */
    private Integer mediaDuration;

    /** 课程有效期，单位天 */
    private Integer validDuration;

    /** 训练动作数量 */
    private Integer sectionNum;

    /** 训练目标（冗余，便于检索） */
    private Integer goalType;

    /** 适用水平：1-零基础 2-初级 3-中级 4-高级 */
    private Integer trainingLevel;

    /** 训练周期总天数 */
    private Integer totalDays;

    /** 建议每周训练次数 */
    private Integer weeklyFrequency;

    /** 所需器械，逗号分隔 */
    private String equipment;

    /** 单次训练预计消耗，单位千卡 */
    private Integer estimatedCalories;

    /** 报名开始时间 */
    private LocalDateTime enrollStartTime;

    /** 报名结束时间 */
    private LocalDateTime enrollEndTime;

    /** 发布次数 */
    private Integer publishTimes;

    /** 最近一次上架时间 */
    private LocalDateTime publishTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long creater;

    private Long updater;

    /** 逻辑删除 */
    private Integer deleted;
}
