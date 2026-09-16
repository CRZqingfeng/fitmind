package com.fitmind.data.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 趋势图数据。
 *
 * <p>结构刻意做成 ECharts 最常见的「x 轴 + 系列」形态：
 * 前端拿到就能直接塞进 option，不用再转换。
 * 这是"给前端的数据结构"该有的样子 ——
 * 不要让他们为了画图再写一层 map。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "趋势图数据")
public class TrendVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "x 轴：日期 yyyyMMdd")
    private List<Integer> dates = new ArrayList<>();

    @Schema(description = "系列：成交订单数")
    private List<Integer> orderCounts = new ArrayList<>();

    @Schema(description = "系列：成交额（分）")
    private List<Long> orderAmounts = new ArrayList<>();

    @Schema(description = "系列：签到次数")
    private List<Integer> signInCounts = new ArrayList<>();

    @Schema(description = "系列：完成训练次数")
    private List<Integer> learnCounts = new ArrayList<>();

    @Schema(description = "系列：体测次数")
    private List<Integer> assessCounts = new ArrayList<>();
}
