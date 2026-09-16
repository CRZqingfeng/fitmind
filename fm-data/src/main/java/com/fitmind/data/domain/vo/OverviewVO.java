package com.fitmind.data.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 运营概览。
 *
 * <p>{@code statDate} 用 int（yyyyMMdd）而不是 Date：
 * 前端画图时它就是个分类轴的标签，用字符串/数字比时间戳直观。</p>
 */
@Data
@Accessors(chain = true)
@Schema(description = "运营概览")
public class OverviewVO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "统计日期 yyyyMMdd")
    private Integer statDate;

    @Schema(description = "签到次数")
    private Integer signInCount;

    @Schema(description = "完成训练次数")
    private Integer learnCount;

    @Schema(description = "小测答对次数")
    private Integer practiceCount;

    @Schema(description = "成交订单数")
    private Integer orderCount;

    @Schema(description = "成交额（分）")
    private Long orderAmount;

    @Schema(description = "退款笔数")
    private Integer refundCount;

    @Schema(description = "退款额（分）")
    private Long refundAmount;

    @Schema(description = "体测次数")
    private Integer assessCount;

    @Schema(description = "净成交额（分）= 成交额 - 退款额")
    private Long netAmount;

    /**
     * 数据来源标记。
     *
     * <p>★ 为什么不直接算好就完事：<b>今日数据有两条来源</b> ——
     * Redis 里的实时计数（今天还没落库）和 MySQL 里的历史行（昨天及以前）。
     * 前端看到"实时"就该知道这个数字还会变，看到"已归档"就知道它定了。
     * 不加这个字段，用户会对着两个口径的数字困惑。</p>
     */
    @Schema(description = "数据来源：realtime-实时计数（还会变） / archived-已落库（不再变） / empty-无数据")
    private String source;

    public Long getNetAmount() {
        long order = orderAmount == null ? 0L : orderAmount;
        long refund = refundAmount == null ? 0L : refundAmount;
        return order - refund;
    }
}
