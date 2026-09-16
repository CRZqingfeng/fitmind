package com.fitmind.api.dto.course;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 训练目录条目（跨服务传输用）。
 *
 * <p>只带学习场景需要的字段：定位（类型/序号/父级）+ 视频信息 + 动作参数。
 * 比.fm-course 内部的 {@code CataVO} 瘦得多——跨服务传输能省则省。</p>
 */
@Data
@Schema(description = "训练目录条目")
public class CourseCatalogueDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "目录id")
    private Long id;

    @Schema(description = "名称：训练日名 / 动作名")
    private String name;

    @Schema(description = "类型：1-训练日 2-训练动作")
    private Integer type;

    @Schema(description = "排序序号")
    private Integer index;

    @Schema(description = "父目录id（训练日的父级为 0）")
    private Long parentCatalogueId;

    @Schema(description = "绑定的媒资id")
    private Long mediaId;

    @Schema(description = "视频时长，单位秒")
    private Integer mediaDuration;

    @Schema(description = "是否可试看")
    private Boolean trailer;

    @Schema(description = "组数")
    private Integer sets;

    @Schema(description = "每组次数")
    private Integer reps;

    @Schema(description = "组间休息，单位秒")
    private Integer restSeconds;

    @Schema(description = "建议负重，单位kg")
    private BigDecimal weight;
}
