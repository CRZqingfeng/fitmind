package com.fitmind.plan.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 运动项目（内容库）。
 *
 * <p><b>最容易写错的地方</b>：{@code kcalPer30min} 是<b>按 65kg 估算</b>的固定值，
 * 不是"这个运动本身的属性"。90kg 的人跑步大约是这个数的 1.4 倍。
 * 所以它不能拿去当"权威消耗"展示给用户看，只能用来算一个量级正确的估计；
 * P2 接体重和 MET 之后要改成按体重算（表里留了 {@code met} 列，P1 全为 NULL）。</p>
 *
 * <p>★ 与 {@code exercise}（动作库）分表：字段重合度 &lt; 30%，
 * 一个要肌群/步骤/示意图，一个只要"每 30 分钟烧多少卡"。</p>
 */
@Data
@Accessors(chain = true)
@TableName("sport")
public class Sport implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    /** 日常/走路/跑步/骑行/有氧操/舞蹈瑜伽/游泳/球类运动/跳绳/爬楼梯/其他 */
    private String category;

    /** 每 30 分钟消耗（按 65kg 估算）
     *  ★ 必须显式 @TableField：MP 的驼峰转下划线**不会**在数字前插下划线，
     *  自动推出来的是 kcal_per30min，与 DDL 的 kcal_per_30min 对不上，
     *  报错是 "Unknown column 'kcal_per30min' in 'field list'"。 */
    @TableField("kcal_per_30min")
    private Integer kcalPer30min;

    /** MET 值；NULL=未录入（P1 不编造） */
    private BigDecimal met;

    private Integer sort;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
