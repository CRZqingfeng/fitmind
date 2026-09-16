package com.fitmind.search.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 检索结果项。
 */
@Data
@NoArgsConstructor
@Schema(description = "课程检索结果")
public class CourseSearchVO {

    @Schema(description = "课程id")
    private Long id;

    @Schema(description = "课程名称（原始值）")
    private String name;

    /**
     * 高亮后的课程名。
     *
     * <p>命中关键词的部分会被 ES 包成 {@code <em>关键词</em>}，
     * 前端直接渲染即可。没命中时为 null。</p>
     */
    @Schema(description = "高亮后的课程名，命中词被 <em> 包裹")
    private String highlightName;

    @Schema(description = "封面地址")
    private String coverUrl;

    @Schema(description = "分类路径，如「增肌 > 胸部 > 哑铃」")
    private String categoryPath;

    @Schema(description = "是否免费")
    private Boolean free;

    @Schema(description = "价格，单位分")
    private Integer price;

    @Schema(description = "课程类型")
    private Integer type;

    @Schema(description = "评分")
    private Integer score;

    @Schema(description = "报名人数")
    private Integer sold;

    @Schema(description = "训练动作数量")
    private Integer sections;

    @Schema(description = "教练姓名")
    private List<String> teacherNames;

    @Schema(description = "上架时间")
    private LocalDateTime publishTime;

    @Schema(description = "★ 训练目标")
    private Integer goalType;

    @Schema(description = "★ 适用水平")
    private Integer trainingLevel;

    @Schema(description = "★ 所需器械")
    private String equipment;

    @Schema(description = "★ 训练周期总天数")
    private Integer totalDays;

    @Schema(description = "★ 视频总时长，单位秒")
    private Integer mediaDuration;

    @Schema(description = "★ 训练动作名称（命中的动作会体现在高亮里）")
    private List<String> actionNames;
}
