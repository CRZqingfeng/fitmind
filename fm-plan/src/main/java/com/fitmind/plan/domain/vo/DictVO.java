package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 字典（前端左侧栏、器械 chips、难度下拉全从这拿）。
 *
 * <p><b>为什么要有这个接口</b>：前端一旦把"胸/背/腿"写死在 .vue 里，
 * 后端改一个字（比如"背部"）就永远筛不出结果，而且没有任何报错 —— 这是最贵的一类 bug。</p>
 *
 * <p>★ {@code categories} 只有 3 项（热身/拉伸/有氧），不含"力量"：
 * 力量类动作在界面上按部位切。左侧栏拼法 = 全部 + categories + bodyParts = 15 项。</p>
 */
@Data
@Schema(description = "计划模块字典")
public class DictVO {

    @Schema(description = "左侧栏分类（热身/拉伸/有氧）")
    private List<String> categories;

    @Schema(description = "11 个部位")
    private List<String> bodyParts;

    @Schema(description = "8 类器械（史密斯/悍马机 P0 无数据）")
    private List<String> equipments;

    @Schema(description = "训练目标")
    private List<String> goals;

    @Schema(description = "难度：0-新手 1-进阶 2-专业")
    private List<OptionVO> difficulties;

    @Schema(description = "组类型：0-正式 1-热身 2-拉伸")
    private List<OptionVO> groupTypes;

    @Schema(description = "星期：1-周一 … 7-周日")
    private List<OptionVO> weekdays;
}
