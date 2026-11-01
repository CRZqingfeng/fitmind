package com.fitmind.plan.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 食物收藏（日志）。
 *
 * <p><b>最容易写错的地方</b>：收藏**不是**食物上的一个 boolean 字段。
 * 一个食物可能被 1000 个人收藏，放在 food 表里就得存 1000 个副本；
 * 而且"我收藏了没"是**跟人相关**的状态，属于日志侧。</p>
 */
@Data
@Accessors(chain = true)
@TableName("food_favorite")
public class FoodFavorite implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long foodId;

    private LocalDateTime createTime;
}
