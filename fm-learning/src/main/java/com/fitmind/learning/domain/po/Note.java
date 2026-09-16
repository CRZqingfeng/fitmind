package com.fitmind.learning.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 学习笔记。
 *
 * <p>「采集」是复制一份私有副本：{@code gathered_note_id} 指向原笔记、
 * {@code is_gathered = 1}、{@code user_id} 变成采集者而 {@code author_id} 保留原作者。</p>
 *
 * <p>⚠️ {@code liked_times} 是本阶段新加的字段 —— 常见实现缺它，
 * 导致「笔记点赞」整条链路静默不存在（互动服务 声明了 NOTE 类型却没人消费）。
 * 现在由 {@code NoteLikedTimesListener} 用<b>覆盖语义</b>异步刷新。</p>
 */
@Data
@Accessors(chain = true)
@TableName("note")
public class Note implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 持有者id（采集后是采集者本人） */
    private Long userId;

    /** 原作者id（采集来的笔记里是别人） */
    private Long authorId;

    private Long courseId;

    /** 训练日id */
    private Long chapterId;

    /** 训练动作id */
    private Long sectionId;

    /** 记录笔记时的视频播放秒数（点笔记跳回该时刻） */
    private Integer noteMoment;

    private String content;

    /** 是否隐私笔记 */
    private Boolean isPrivate;

    /** 是否被折叠（管理端） */
    private Boolean hidden;

    /** 被折叠原因 */
    private String hiddenReason;

    /** 被采集的原笔记id */
    private Long gatheredNoteId;

    /** 本条是否是采集他人的笔记 */
    private Boolean isGathered;

    /** 点赞数（由 fm-remark 异步同步，覆盖更新） */
    private Integer likedTimes;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /**
     * 非数据库字段：<b>当前查询用户</b>是否采集过这条笔记。
     *
     * <p>由列表 SQL 里的 {@code EXISTS} 子查询算出。注意它和本行的 {@code isGathered}
     * 完全是两件事：{@code isGathered} 说的是"我这条是不是从别人那儿采集来的"，
     * 而本字段说的是"我有没有采集过这一条"。常见实现只有一个字段，语义是混的。</p>
     */
    @TableField(exist = false)
    private Boolean gatheredByMe;

    /**
     * 非数据库字段：这条笔记被多少人采集过（仅管理端分页用）。
     */
    @TableField(exist = false)
    private Integer usedTimes;
}
