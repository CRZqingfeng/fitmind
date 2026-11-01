package com.fitmind.plan.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 运动消耗记录（日志，<b>必须快照</b>）。
 *
 * <p><b>最容易写错的地方</b>：一次运动一行，<b>不合并</b>。
 * 同一天跑两次 30 分钟是两条记录（各自有时长，用户能删掉其中一条）；
 * 而"记饮食"要合并 —— 两者的差别不是风格，是语义：
 * 饮食的重复记录几乎都是"又吃了半份"，运动的重复记录是"真的又跑了一次"。</p>
 */
@Data
@Accessors(chain = true)
@TableName("sport_log")
public class SportLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private LocalDate logDate;

    private Long sportId;

    /** 运动名快照 */
    private String sportName;

    private Integer durationMin;

    /** 本次消耗快照 */
    private Integer kcal;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
