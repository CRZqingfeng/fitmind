package com.fitmind.learning.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 积分榜单行。
 *
 * <p>⚠️ {@code @TableName("points_board")} 是<b>逻辑表名</b>：查历史赛季时由
 * {@code DynamicTableNameInnerInterceptor} 结合 {@code TableInfoContext}
 * 把物理表名替换成 {@code points_board_{season}}。</p>
 *
 * <p>{@code id} 在分表里就是<b>排名</b>——结算是按 ZSet 从高到低分页插入的，
 * 自增 id 天然等于排名（复刻常见实现的做法，省掉 rank 列的维护）。</p>
 */
@Data
@Accessors(chain = true)
@TableName("points_board")
public class PointsBoard implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long userId;

    private Integer points;

    /** 非数据库字段：排名（Redis 实时榜查询时计算出来的） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Integer rank;

    /** 非数据库字段：用户名（批量补齐） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String name;
}
