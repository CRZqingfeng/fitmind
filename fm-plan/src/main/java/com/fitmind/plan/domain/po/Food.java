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
 * 食物（内容库）。
 *
 * <p><b>最容易写错的地方</b>：{@code category} 只有 8 个**真分类**，
 * "常见 / 搜索 / 自定义 / 收藏"是**入口**，不是分类。
 * 把"收藏"塞进 category 会有两个后果：① 同一个食物既属于"主食"又属于"收藏"，
 * 一张表表达不了；② 用户取消收藏后这条食物在库里"消失"了。
 * 入口该用 {@code scope} 参数表达（见 {@code FoodQuery}）。</p>
 *
 * <p>★ {@code unitsJson} 只放"默认单位 + 最多两个常用单位"，<b>不做任意单位换算引擎</b>。
 * 做多了的下场：某个食物被填成"1 碗 = 1 克"，页面上看不出来，只有数字不对。</p>
 */
@Data
@Accessors(chain = true)
@TableName("food")
public class Food implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;

    /** 别名（从"黄瓜（鲜）［胡瓜］"里解析出来） */
    private String alias;

    private String category;

    @TableField("kcal_per_100g")
    private Integer kcalPer100g;

    @TableField("protein_per_100g")
    private BigDecimal proteinPer100g;

    @TableField("carb_per_100g")
    private BigDecimal carbPer100g;

    @TableField("fat_per_100g")
    private BigDecimal fatPer100g;

    /** [{"unit":"克","gram":1},{"unit":"碗","gram":150}] */
    private String unitsJson;

    private String defaultUnit;

    /** 展示用 emoji（不是营养数据） */
    private String emoji;

    private String imageUrl;

    private Integer sort;

    /** 0=公共；>0=用户自定义 */
    private Long ownerId;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
