package com.fitmind.learning.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 积分赛季。
 *
 * <p>id 同时作为分表后缀：{@code points_board_{id}}。</p>
 */
@Data
@Accessors(chain = true)
@TableName("points_board_season")
public class PointsBoardSeason implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private String name;

    private LocalDate beginTime;

    private LocalDate endTime;
}
