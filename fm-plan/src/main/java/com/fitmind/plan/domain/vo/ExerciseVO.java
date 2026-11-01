package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 动作库列表项。
 *
 * <p>列表只给卡片需要的字段（图 / 名 / 标签），详情才带肌群与步骤 —— 876 条时
 * 每条都带 5 步中文说明，一页 20 条就是 100 段文本，纯属浪费带宽。</p>
 */
@Data
@Schema(description = "动作库列表项")
public class ExerciseVO {

    @Schema(description = "动作id")
    private Long id;

    @Schema(description = "中文名")
    private String name;

    @Schema(description = "英文名")
    private String enName;

    @Schema(description = "分类")
    private String category;

    @Schema(description = "部位")
    private String bodyPart;

    @Schema(description = "器械")
    private String equipment;

    @Schema(description = "难度 1~5")
    private Integer difficulty;

    @Schema(description = "示意图")
    private String imageUrl;
}
