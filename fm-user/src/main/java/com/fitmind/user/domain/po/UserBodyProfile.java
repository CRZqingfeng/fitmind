package com.fitmind.user.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fitmind.user.enums.GoalType;
import com.fitmind.user.enums.TrainingLevel;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户身体档案 ★ 健身场景新增。
 *
 * <p>这份档案是整个健身平台的「用户侧核心数据」：训练计划制定、体测评估、
 * 以及后续 AI 健身教练的课程推荐，都要以它为依据。</p>
 *
 * <p>其中 {@code bmi} 由后端根据身高体重自动计算，不接受前端传值，
 * 避免出现「身高体重变了但 BMI 没变」的脏数据。</p>
 */
@Data
@Accessors(chain = true)
@TableName("user_body_profile")
public class UserBodyProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户 id */
    private Long userId;

    /** 身高（cm） */
    private BigDecimal height;

    /** 当前体重（kg） */
    private BigDecimal weight;

    /** 目标体重（kg） */
    private BigDecimal targetWeight;

    /** BMI（后端自动计算） */
    private BigDecimal bmi;

    /** 体脂率（%） */
    private BigDecimal bodyFatRate;

    /** 腰围（cm） */
    private BigDecimal waist;

    /** 胸围（cm） */
    private BigDecimal chest;

    /** 臂围（cm） */
    private BigDecimal arm;

    /** 臀围（cm） */
    private BigDecimal hip;

    /** 训练目标：1-增肌，2-减脂，3-塑形，4-保持 */
    private GoalType goalType;

    /** 训练水平：1-零基础，2-初级，3-中级，4-高级 */
    private TrainingLevel trainingLevel;

    /** 每周可训练次数 */
    private Integer weeklyFrequency;

    /** 伤病史（AI 教练推荐时会参考） */
    private String injuryHistory;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
