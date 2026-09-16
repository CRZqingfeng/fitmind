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
}
