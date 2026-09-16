package com.fitmind.course.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 训练目录（正式表）。
 *
 * <p><b>健身化语义</b>：章 = 训练日，节 = 训练动作。
 * 所以这份目录本身就是一份训练计划。</p>
 *
 * <pre>
 *   训练日 Day 1（type=1，parentCatalogueId=0）
 *     ├── 哑铃卧推（type=2，parentCatalogueId=Day1 的 id）
 *     │     组数 4 / 次数 10 / 组间休息 90s / 建议负重 20kg
 *     └── 俯卧撑（type=2）
 * </pre>
 */
@Data
@Accessors(chain = true)
@TableName("course_catalogue")
public class CourseCatalogue implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键，由代码生成（雪花 id，因为父子要一次性算好） */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 名称：训练日名 / 动作名 */
    private String name;

    /** 课程 id */
    private Long courseId;

    /** 类型：1-训练日 2-训练动作 3-测试 */
    private Integer type;

    /** 父目录 id，训练日为 0 */
    private Long parentCatalogueId;

    /** 排序序号 */
    private Integer cIndex;

    /** ★ 关联 fm-media 的媒资 id */
    private Long mediaId;

    /** 视频名（上架完整性校验用） */
    private String videoName;

    /** 视频时长，单位秒 */
    private Integer mediaDuration;

    /** 是否可试看：1-是 0-否 */
    private Integer trailer;

    // ==================== ★★ 健身专属：动作参数 ====================

    /** 组数 */
    private Integer sets;

    /** 每组次数 */
    private Integer reps;

    /** 组间休息，单位秒 */
    private Integer restSeconds;

    /** 建议负重，单位 kg */
    private BigDecimal weight;

    /** 计时类动作时长，单位秒（平板支撑等，与 reps 二选一） */
    private Integer durationSeconds;

    /** 动作类型：1-力量 2-有氧 3-拉伸 */
    private Integer actionType;

    /** 该动作所需器械 */
    private String equipment;

    /** 目标肌群 */
    private String targetMuscles;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long creater;

    private Long updater;

    private Integer deleted;
}
