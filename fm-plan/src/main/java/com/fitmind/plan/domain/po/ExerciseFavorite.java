package com.fitmind.plan.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 我的动作收藏（日志）。
 *
 * <p>P0 只建表 + 定义唯一索引，接口留到 P1（设计稿里"我的动作"入口在动作库左侧栏）。</p>
 */
@Data
@Accessors(chain = true)
@TableName("exercise_favorite")
public class ExerciseFavorite implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long exerciseId;

    private LocalDateTime createTime;
}
