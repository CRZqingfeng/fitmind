package com.fitmind.data.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 每日运营数据汇总。
 *
 * <p>由定时任务从 Redis 落库。Redis 扛高频累加，MySQL 存可查的历史。
 * 与 M3-2 的积分榜（Redis ZSet 实时榜 + 月度分表落库）是同一套模式。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("data_daily")
public class DataDaily implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 统计日期 yyyyMMdd */
    private Integer statDate;

    /** 签到次数 */
    private Integer signInCount;

    /** 完成训练次数 */
    private Integer learnCount;

    /** 小测答对次数 */
    private Integer practiceCount;

    /** 成交订单数 */
    private Integer orderCount;

    /** 成交额（分） */
    private Long orderAmount;

    /** 退款笔数 */
    private Integer refundCount;

    /** 退款额（分） */
    private Long refundAmount;

    /** 体测次数 */
    private Integer assessCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
