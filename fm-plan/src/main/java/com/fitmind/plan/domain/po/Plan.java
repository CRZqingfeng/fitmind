package com.fitmind.plan.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 训练计划（模板）。
 *
 * <p><b>最容易写错的地方</b>：这张表是**模板**，用户改计划时改的是它下面的 plan_day_item，
 * 不是这里的字段；而用户的训练记录（workout_session / workout_set_log）**永远不 join 回这里**
 * 去做"当时的组数是多少"——历史必须靠日志表自己的快照，否则官方把计划从 4 组改成 5 组，
 * 去年的训练量统计会跟着变。</p>
 */
@Data
@Accessors(chain = true)
@TableName("plan")
public class Plan implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 计划名，默认"未命名计划"（默认值后端给） */
    private String title;

    /** 一句话描述（详情页副标题） */
    private String summary;

    private String coverUrl;

    /** 训练目标：增肌 / 减脂 */
    private String goal;

    /** 难度：0-新手 1-进阶 2-专业 */
    private Integer difficulty;

    /** 一周几练（写时按 plan_day 数量回填） */
    private Integer trainDaysPerWeek;

    /** 预估千卡；0 = 暂无估算（缺 exercise.heat，P1 再接体重与 MET） */
    private Integer kcalEstimate;

    /** 器械清单 JSON 数组，如 ["杠铃","哑铃"] */
    private String equipmentJson;

    /** 来源：0-官方 1-自建 2-AI */
    private Integer source;

    /** 状态：0-草稿 1-已发布 2-已下架 */
    private Integer status;

    /** 被执行次数（热度，只增） */
    private Integer refCount;

    /** 归属用户，0 = 官方 */
    private Long ownerId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
