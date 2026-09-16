package com.fitmind.course.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 课程分类，三级树。
 *
 * <pre>
 * 一级：训练目标   增肌 / 减脂 / 塑形 / 保持
 * 二级：训练部位   胸 / 背 / 腿 / 肩 / 手臂 / 核心 / 臀 / 全身
 * 三级：器械场景   徒手 / 哑铃 / 单杠 / 器械 / 弹力带 / 有氧器械
 * </pre>
 *
 * <p><b>为什么一级用「训练目标」</b>：与用户身体档案的 {@code goal_type} 语义一致，
 * 后续 AI 教练（M7）可以按「目标 + 器械」直接匹配课程，不需要额外的映射表。</p>
 */
@Data
@Accessors(chain = true)
@TableName("category")
public class Category implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 分类名称 */
    private String name;

    /** 父分类 id，一级为 0 */
    private Long parentId;

    /** 层级：1-训练目标 2-训练部位 3-器械场景 */
    private Integer level;

    /** 排序优先级，越大越靠前 */
    private Integer priority;

    /** 状态：1-启用 2-禁用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Long creater;

    private Long updater;

    /** 逻辑删除 */
    private Integer deleted;
}
