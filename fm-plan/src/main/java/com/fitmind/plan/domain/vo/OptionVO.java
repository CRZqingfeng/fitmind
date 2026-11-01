package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 字典项（值 + 中文标签）。
 *
 * <p>为什么字典不直接返回 {@code List<String>}：难度这种字段的值是 0/1/2，
 * 直接给字符串就没法回传了；给 {@code {value:0, label:"新手"}} 前端既能展示又能提交。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "字典项")
public class OptionVO {

    @Schema(description = "提交用值", example = "0")
    private Object value;

    @Schema(description = "展示用标签", example = "新手")
    private String label;
}
