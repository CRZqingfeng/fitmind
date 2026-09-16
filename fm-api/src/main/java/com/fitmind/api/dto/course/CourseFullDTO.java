package com.fitmind.api.dto.course;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 课程全文（跨服务传输用）。
 *
 * <p>供 fm-learning 在<b>报名</b>（校验上架状态 / 免费 / 有效期 / 总动作数）
 * 和<b>记录学习</b>（判断是否全部完成）时使用。</p>
 *
 * <p>与 fm-search 用的 {@code CourseSearchInfoDTO} 的区别：
 * 那份是为"组装检索文档"设计的（带分类名/教练/聚合文本），
 * 这份是为"学习业务"设计的（带有效期/总动作数/完整目录树）。
 * 两个消费方各取所需，不共用一个 DTO。</p>
 */
@Data
@Schema(description = "课程全文")
public class CourseFullDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "课程id")
    private Long id;

    @Schema(description = "课程名称")
    private String name;

    @Schema(description = "封面地址")
    private String coverUrl;

    @Schema(description = "课程类型：1-训练计划课 2-单动作教学 3-理论饮食课")
    private Integer courseType;

    @Schema(description = "是否免费：1-免费 2-付费")
    private Integer free;

    @Schema(description = "价格，单位分")
    private Integer price;

    @Schema(description = "状态：2-已上架 3-下架 4-已完结")
    private Integer status;

    @Schema(description = "训练动作总数（判断课程是否学完）")
    private Integer sectionNum;

    @Schema(description = "★ 课程有效期，单位天（报名时算 expireTime）")
    private Integer validDuration;

    @Schema(description = "训练目标：1-增肌 2-减脂 3-塑形 4-保持")
    private Integer goalType;

    @Schema(description = "适用水平：1-零基础 2-初级 3-中级 4-高级")
    private Integer trainingLevel;

    @Schema(description = "训练周期总天数")
    private Integer totalDays;

    @Schema(description = "建议每周训练次数（默认学习计划可参考）")
    private Integer weeklyFrequency;

    @Schema(description = "训练目录（仅 withCatalogues=true 时返回）")
    private List<CourseCatalogueDTO> catalogues;
}
