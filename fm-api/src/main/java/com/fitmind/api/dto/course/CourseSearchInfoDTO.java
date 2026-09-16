package com.fitmind.api.dto.course;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 课程检索信息（跨服务传输用）。
 *
 * <p>fm-search 消费课程上下架消息后，通过 Feign 调 fm-course 的
 * {@code /courses/internal/{id}/searchInfo} 拿到这份数据，
 * 再组装成 ES 文档写入索引。</p>
 *
 * <p>为什么把「分类名 / 教练名 / 训练动作」一起返回，而不是让 fm-search 自己拼：
 * 组装 ES 文档需要多个服务的数据，如果 fm-search 挨个去调，
 * 一次索引同步会变成 4~5 次跨服务调用。<b>让数据的归属方一次性聚合好</b>更省事，
 * fm-search 只负责"把这份数据翻译成 ES 文档"。</p>
 */
@Data
@Schema(description = "课程检索信息")
public class CourseSearchInfoDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    // ==================== 基本信息 ====================

    @Schema(description = "课程id")
    private Long id;

    @Schema(description = "课程名称")
    private String name;

    @Schema(description = "课程类型：1-训练计划课 2-单动作教学 3-理论饮食课")
    private Integer courseType;

    @Schema(description = "封面地址")
    private String coverUrl;

    @Schema(description = "是否免费：1-免费 2-付费")
    private Integer free;

    @Schema(description = "价格，单位分")
    private Integer price;

    @Schema(description = "状态：2-已上架 4-已完结")
    private Integer status;

    // ==================== 分类（三级） ====================

    @Schema(description = "一级分类id（训练目标）")
    private Long firstCateId;

    @Schema(description = "一级分类名（训练目标），如「增肌」")
    private String firstCateName;

    @Schema(description = "二级分类id（训练部位）")
    private Long secondCateId;

    @Schema(description = "二级分类名（训练部位），如「胸部」")
    private String secondCateName;

    @Schema(description = "三级分类id（器械场景）")
    private Long thirdCateId;

    @Schema(description = "三级分类名（器械场景），如「哑铃」")
    private String thirdCateName;

    @Schema(description = "分类路径，如「增肌 > 胸部 > 哑铃」，检索结果直接展示")
    private String categoryPath;

    // ==================== ★ 健身化检索维度 ====================

    @Schema(description = "训练目标：1-增肌 2-减脂 3-塑形 4-保持")
    private Integer goalType;

    @Schema(description = "适用水平：1-零基础 2-初级 3-中级 4-高级")
    private Integer trainingLevel;

    @Schema(description = "训练周期总天数")
    private Integer totalDays;

    @Schema(description = "建议每周训练次数")
    private Integer weeklyFrequency;

    @Schema(description = "单次训练预计消耗，单位千卡")
    private Integer estimatedCalories;

    @Schema(description = "所需器械，逗号分隔")
    private String equipment;

    // ==================== 统计 ====================

    @Schema(description = "视频总时长，单位秒")
    private Integer mediaDuration;

    @Schema(description = "训练动作数量")
    private Integer sectionNum;

    @Schema(description = "课程评分")
    private Integer score;

    @Schema(description = "发布次数")
    private Integer publishTimes;

    // ==================== 时间 ====================

    @Schema(description = "上架时间")
    private LocalDateTime publishTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    // ==================== 聚合内容 ====================

    @Schema(description = "授课教练")
    private List<TeacherInfo> teachers;

    @Schema(description = "训练日名称列表，如「Day 1 胸部+三头」")
    private List<String> trainingDays;

    @Schema(description = "训练动作名称列表，如「哑铃卧推」")
    private List<String> actionNames;

    @Schema(description = "训练动作明细（含动作参数）")
    private List<ActionInfo> actions;

    /**
     * 教练信息。
     */
    @Data
    @Schema(description = "教练信息")
    public static class TeacherInfo implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "教练用户id")
        private Long id;

        @Schema(description = "姓名")
        private String name;

        @Schema(description = "头像")
        private String icon;
    }

    /**
     * 训练动作明细。
     *
     * <p>这些参数（组数/次数/休息/负重）是 M2-2b 为「目录即训练计划」设计的，
     * 落到 ES 后可以支撑「按动作名搜课程」以及后续训练计划的展示。</p>
     */
    @Data
    @Schema(description = "训练动作明细")
    public static class ActionInfo implements Serializable {

        private static final long serialVersionUID = 1L;

        @Schema(description = "动作名")
        private String name;

        @Schema(description = "所属训练日名")
        private String dayName;

        @Schema(description = "视频时长，单位秒")
        private Integer mediaDuration;

        @Schema(description = "组数")
        private Integer sets;

        @Schema(description = "每组次数")
        private Integer reps;

        @Schema(description = "组间休息，单位秒")
        private Integer restSeconds;

        @Schema(description = "建议负重，单位kg")
        private BigDecimal weight;

        @Schema(description = "动作类型：1-力量 2-有氧 3-拉伸")
        private Integer actionType;

        @Schema(description = "目标肌群")
        private String targetMuscles;
    }
}
