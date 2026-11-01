package com.fitmind.plan.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 饮食计划（模板）。
 *
 * <p><b>最容易写错的地方</b>：这不是 {@code plan}（训练计划）换了个名字。
 * 字段重合度不到 30%（这边 cycle_days / kcal_per_day，那边 train_days_per_week / difficulty /
 * equipment_json），硬把两者合并成一张"计划表"会造出一堆永远为 NULL 的列，
 * 而且哪天有人给饮食计划加个"每日餐数"，训练计划也跟着多一个没用的字段。</p>
 */
@Data
@Accessors(chain = true)
@TableName("diet_plan")
public class DietPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String title;

    private String summary;

    private String coverUrl;

    /** 增肌 / 减脂 */
    private String goal;

    /** 一周期几天 */
    private Integer cycleDays;

    /** 每日热量（各天平均，导入时算出，不手填） */
    private Integer kcalPerDay;

    /** 0-官方 1-自建 2-AI */
    private Integer source;

    private Integer status;

    private Integer refCount;

    /** 0=官方 */
    private Long ownerId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
