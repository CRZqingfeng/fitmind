package com.fitmind.plan.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日饮食记录（日志，<b>必须快照</b>）。
 *
 * <p><b>最容易写错的地方</b>：合并（同一天同一餐同一食物同一单位再记一次）时，
 * {@code amount / gram / kcal / proteinG / carbG / fatG} <b>六列必须一起按新增量累加</b>。
 * 只加 {@code amount} 的典型错误：用户看到 50g 变 100g，热量还是 46 千卡 ——
 * 页面上很难一眼看出来，但"还可以吃"已经算错了。</p>
 *
 * <p>★ 为什么必须快照：食物库的数值会被运营修正。只存 {@code foodId} 的话，
 * 去年 12 月的记录会跟着变成今年的价，用户的历史热量凭空变化。</p>
 */
@Data
@Accessors(chain = true)
@TableName("diet_log")
public class DietLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LocalDate logDate;

    /** 1早 2午 3晚 4加餐（记录侧只有这 4 个，没有 mealIndex） */
    private Integer mealType;

    private Long foodId;

    /** 食物名快照 */
    private String foodName;

    private BigDecimal amount;

    private String unit;

    /** 折算成克 */
    private BigDecimal gram;

    /** 热量快照 */
    private Integer kcal;

    private BigDecimal proteinG;

    private BigDecimal carbG;

    private BigDecimal fatG;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
