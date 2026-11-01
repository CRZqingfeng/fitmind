package com.fitmind.plan.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 某天摄入合计（SQL 聚合的投影对象）。
 *
 * <p>为什么不让 Service 把当天的 record 全查回来在 Java 里加：
 * 一天记 20 条还能接受，但"累计一个月"这类统计就会变成几百行的传输 + 遍历。
 * SUM 交给数据库，是这类聚合唯一合理的做法。</p>
 */
@Data
@Schema(description = "摄入合计")
public class MacroSumVO {

    @Schema(description = "热量合计")
    private Integer kcal;

    @Schema(description = "蛋白合计（克）")
    private BigDecimal protein;

    @Schema(description = "碳水合计（克）")
    private BigDecimal carb;

    @Schema(description = "脂肪合计（克）")
    private BigDecimal fat;
}
