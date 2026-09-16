package com.fitmind.search.domain.po;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 课程 ES 文档。
 *
 * <p>这是<b>索引里的文档</b>，不是数据库表。</p>
 *
 * <p>为什么用 RestHighLevelClient 而不是 Spring Data ES：
 * 后者会把 Spring Data 的版本和 ES 客户端版本绑在一起，
 * 容易出现「服务端 7.17 而客户端被 Boot 管到 8.x」的错配。
 * 代价就是<b>mapping 得手写</b>（见 {@code IndexInitializer}），
 * 换来的好处是版本可控、依赖干净。</p>
 *
 * <p>带 ★ 的字段是健身化的检索维度，支撑「按目标 + 器械 + 动作」找课程。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "课程索引文档")
public class CourseDoc {

    @Schema(description = "课程id")
    private Long id;

    @Schema(description = "课程名称（搜索主字段）")
    private String name;

    @Schema(description = "一级分类id（训练目标）")
    private Long categoryIdLv1;

    @Schema(description = "二级分类id（训练部位）")
    private Long categoryIdLv2;

    @Schema(description = "三级分类id（器械场景）")
    private Long categoryIdLv3;

    @Schema(description = "分类路径，如「增肌 > 胸部 > 哑铃」")
    private String categoryPath;

    @Schema(description = "是否免费")
    private Boolean free;

    @Schema(description = "课程类型：1-训练计划课 2-单动作教学 3-理论饮食课")
    private Integer type;

    @Schema(description = "价格，单位分")
    private Integer price;

    @Schema(description = "评分")
    private Integer score;

    @Schema(description = "报名人数（热门排序用；M4 交易模块上线后才会有真实值，当前为 0）")
    private Integer sold;

    @Schema(description = "教练id列表")
    private List<Long> teacherIds;

    @Schema(description = "教练姓名列表")
    private List<String> teacherNames;

    @Schema(description = "训练动作数量")
    private Integer sections;

    @Schema(description = "封面地址（不参与搜索）")
    private String coverUrl;

    @Schema(description = "上架时间")
    private LocalDateTime publishTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    // ==================== ★ 健身化字段 ====================

    @Schema(description = "训练目标：1-增肌 2-减脂 3-塑形 4-保持")
    private Integer goalType;

    @Schema(description = "适用水平：1-零基础 2-初级 3-中级 4-高级")
    private Integer trainingLevel;

    @Schema(description = "所需器械，逗号分隔")
    private String equipment;

    @Schema(description = "训练周期总天数")
    private Integer totalDays;

    @Schema(description = "视频总时长，单位秒")
    private Integer mediaDuration;

    @Schema(description = "训练日名称列表")
    private List<String> trainingDays;

    @Schema(description = "训练动作名称列表")
    private List<String> actionNames;
}
