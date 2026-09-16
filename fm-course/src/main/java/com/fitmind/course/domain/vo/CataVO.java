package com.fitmind.course.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 训练目录（树形）。
 *
 * <p>{@code sections} 在训练日节点上是「该天的所有训练动作」，
 * 在动作节点上为空。</p>
 *
 * <p><b>⚠️ id 为什么要序列化成字符串</b>：目录 id 用的是雪花算法生成的 19 位数字，
 * 而 JavaScript 的 Number 最大安全整数只有 16 位（2^53-1）。
 * 直接把 Long 丢给前端，前端解析时会精度丢失——再传回来就对不上了，
 * 表现为「明明是这个动作，服务端却说找不到」。
 * 所以这几个 id 字段统一用 {@link ToStringSerializer} 转成字符串。</p>
 */
@Data
@Schema(description = "训练目录")
public class CataVO {

    @Schema(description = "目录id（字符串形式，避免前端精度丢失）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @Schema(description = "序号")
    private Integer index;

    @Schema(description = "名称：训练日名 / 动作名")
    private String name;

    @Schema(description = "类型：1-训练日 2-训练动作")
    private Integer type;

    @Schema(description = "绑定的媒资id（字符串形式，避免前端精度丢失）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long mediaId;

    @Schema(description = "视频名称")
    private String mediaName;

    @Schema(description = "视频时长，单位秒")
    private Integer mediaDuration;

    @Schema(description = "是否可试看")
    private Boolean trailer;

    // ==================== ★★ 动作参数 ====================

    @Schema(description = "组数")
    private Integer sets;

    @Schema(description = "每组次数")
    private Integer reps;

    @Schema(description = "组间休息，单位秒")
    private Integer restSeconds;

    @Schema(description = "建议负重，单位kg")
    private BigDecimal weight;

    @Schema(description = "计时类动作时长，单位秒")
    private Integer durationSeconds;

    @Schema(description = "动作类型：1-力量 2-有氧 3-拉伸")
    private Integer actionType;

    @Schema(description = "所需器械")
    private String equipment;

    @Schema(description = "目标肌群")
    private String targetMuscles;

    // ==================== 动作小测 ====================
    @Schema(description = "该节点下的小测题目数（0 表示还没配小测）")
    private Integer subjectNum;
    // ==================== 结构 ====================

    @Schema(description = "子目录（训练日下面的训练动作）")
    private List<CataVO> sections;

    @Schema(description = "是否可修改（已上架的目录受保护）")
    private Boolean canUpdate = false;

    @Schema(description = "已上架的训练日最大序号（前端据此提示哪里不能动）")
    private Integer maxIndexOnShelf;

    @Schema(description = "已上架的动作最大序号")
    private Integer maxSectionIndexOnShelf;
}
