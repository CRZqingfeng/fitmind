package com.fitmind.api.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 用户身体档案 DTO（跨服务传输）。
 *
 * <p><b>★ 为什么单独一个 DTO，而不是往 {@link UserDTO} 里再塞字段</b>：
 * {@code gender} / {@code birthday} 属于"用户信息"（user_detail），
 * 而 {@code weeklyFrequency} / {@code trainingLevel} 属于"健身档案"
 * （user_body_profile）—— 两张表、两个领域概念。
 * 混进一个 DTO 里，调用方就分不清哪些字段是"一定有"的：
 * 用户可以不填档案（此时 {@code weeklyFrequency} 为 null），
 * 但性别是注册就带的。分开之后这个差别在类型上就看得见。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "用户身体档案")
public class UserBodyProfileDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "用户id")
    private Long userId;

    @Schema(description = "身高（cm）")
    private java.math.BigDecimal height;

    @Schema(description = "体重（kg）")
    private java.math.BigDecimal weight;

    @Schema(description = "目标体重（kg）")
    private java.math.BigDecimal targetWeight;

    /**
     * 每周训练次数。
     *
     * <p>★ 它是 <b>TDEE 活动系数的唯一来源</b>：每周 1~2 次 → 1.375、
     * 3~4 次 → 1.55、5~6 次 → 1.725。拿不到就只能退回"久坐 1.2"，
     * 那对一个每周练 4 次的人来说，TDEE 会被低估约 25%（≈500 kcal/天）——
     * 而推荐摄入量是直接建立在 TDEE 上的，所以这个字段值得专门开一个接口去取。</p>
     */
    @Schema(description = "每周训练次数（TDEE 活动系数的来源）")
    private Integer weeklyFrequency;

    @Schema(description = "训练水平：1-新手 2-进阶 3-高级")
    private Integer trainingLevel;

    @Schema(description = "伤病史")
    private String injuryHistory;

    // ==================== M6-4 新增（AI 教练用） ====================
    // ★ 这三个字段是"用户档案 → AI 上下文"这条链路的输入。
    //   加它们而不是在 AI 侧"猜"目标，理由很实在：
    //   训练目标是**用户自己填的**（goal_type），而"靠目标体重 vs 当前体重推导"
    //   会算错两种情况 —— 想同时增肌减脂的（目标体重=当前体重）、
    //   以及没填目标体重的（直接推导不出来，退化成"通用"，个性化就丢了）。
    //   ★ 数据在哪就以哪为准，不要在消费方做启发式替代。

    /**
     * 训练目标：1-增肌 2-减脂 3-塑形 4-保持。
     *
     * <p>★ 它是 RAG <b>元数据过滤</b>的依据：目标是减脂的用户，
     * 检索时应该排除"增肌期怎么吃"这类文档 ——
     * 这比调 topK 有效得多（topK 只能控制"给几条"，控制不了"给的对不对"）。</p>
     */
    @Schema(description = "训练目标：1-增肌 2-减脂 3-塑形 4-保持")
    private Integer goalType;

    /** BMI（后端算好的，AI 直接引用，不要自己拿身高体重再算一遍） */
    @Schema(description = "BMI")
    private java.math.BigDecimal bmi;

    /** 体脂率（%） */
    @Schema(description = "体脂率（%）")
    private java.math.BigDecimal bodyFatRate;
}
