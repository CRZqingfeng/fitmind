package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 运动项目（列表项）。
 *
 * <p>★ {@code kcalText}（"260 千卡/30 分钟"）由后端拼：抄一份在前端，
 * 以后改成"按体重算"就要动两处。</p>
 */
@Data
@Schema(description = "运动项目")
public class SportVO {

    @Schema(description = "运动id")
    private Long id;

    @Schema(description = "运动名")
    private String name;

    @Schema(description = "分类")
    private String category;

    @Schema(description = "每 30 分钟消耗（按 65kg 估算）")
    private Integer kcalPer30min;

    @Schema(description = "展示串：260 千卡/30分钟")
    private String kcalText;

    @Schema(description = "分类标签列表（前端统一渲染）")
    private List<String> tags;
}
