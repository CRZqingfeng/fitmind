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
 * 训练目录（草稿表）。
 *
 * <p>与 {@link CourseCatalogue} 主键相同，随课程一起上架 / 下架。</p>
 */
@Data
@Accessors(chain = true)
@TableName("course_catalogue_draft")
public class CourseCatalogueDraft implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 名称：训练日名 / 动作名 */
    private String name;

    /** 课程 id */
    private Long courseId;

    /** 类型：1-训练日 2-训练动作 3-测试 */
    private Integer type;

    /** 父目录 id */
    private Long parentCatalogueId;

    /** 排序序号 */
    private Integer cIndex;

    /** 关联媒资 id */
    private Long mediaId;

    /** 视频名 */
    private String videoName;

    /** 视频时长，单位秒 */
    private Integer mediaDuration;

    /** 是否可试看 */
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

    /** 计时类动作时长，单位秒 */
    private Integer durationSeconds;

    /** 动作类型：1-力量 2-有氧 3-拉伸 */
    private Integer actionType;

    /** 该动作所需器械 */
    private String equipment;

    /** 目标肌群 */
    private String targetMuscles;

    /** 是否可修改 */
    private Integer canUpdate;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long creater;

    private Long updater;

    /**
     * 设置目录的基本信息。
     *
     * <p>保存目录时<b>只覆盖这几个字段</b>，mediaId / 动作参数等业务字段要保留——
     * 这是「全量覆盖（先删后插）却不丢数据」的关键。</p>
     *
     * @param index          序号
     * @param name           名称
     * @param type           类型
     * @param parentCataId   父目录 id
     * @param courseId       课程 id
     */
    public CourseCatalogueDraft setCataBaseInfo(Integer index, String name, Integer type,
                                                Long parentCataId, Long courseId) {
        this.cIndex = index;
        this.name = name;
        this.type = type;
        this.parentCatalogueId = parentCataId;
        this.courseId = courseId;
        return this;
    }
}
