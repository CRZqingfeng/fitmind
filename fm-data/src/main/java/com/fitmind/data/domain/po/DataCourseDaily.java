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
 * 每日课程销量统计（销量榜的数据源）。
 *
 * <p>为什么单独一张表而不是塞进 {@code data_daily}：
 * 全局汇总一天只有<b>一行</b>，课程维度一天有 <b>N 行</b> ——
 * 粒度不同。硬塞进去要么把 N 行冗余进一行（列爆炸），
 * 要么塞成 JSON（没法按课程查历史），两种都不好。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("data_course_daily")
public class DataCourseDaily implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 统计日期 yyyyMMdd */
    private Integer statDate;

    /** 课程id */
    private Long courseId;

    /** 销量 */
    private Integer orderCount;

    /** 销售额（分） */
    private Long orderAmount;

    /** 退款笔数 */
    private Integer refundCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
