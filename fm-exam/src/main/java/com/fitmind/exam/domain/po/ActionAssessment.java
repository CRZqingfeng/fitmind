package com.fitmind.exam.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 动作考核。
 *
 * <p><b>★ 为什么是教练人工评分，而不是规则自动判分</b>：
 * 动作做得标不标准（关节角度、发力顺序、有没有代偿）本质是视觉判断，
 * 规则写不出来；而"知识类"的自动判分 M2-2c 已经做过
 * （课程侧 {@code course_subject} + 服务端判分 + 答对发积分），
 * 这里再做一遍就是重复。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("action_assessment")
public class ActionAssessment implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 所属课程（可空：允许练平台上的任意动作） */
    private Long courseId;

    /** 所属训练动作（目录 id） */
    private Long cataId;

    /** 媒资id（复用 fm-media） */
    private Long mediaId;

    private String videoUrl;

    /** 学员自述 */
    private String remark;

    /** 状态：1-待评分 2-已评分 */
    private Integer status;

    /** 教练评分 0-100 */
    private Integer score;

    private String comment;

    private Long coachId;

    private LocalDateTime submitTime;

    private LocalDateTime reviewTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 待评分 */
    public boolean pending() {
        return status != null && status == 1;
    }

    /** 已评分 */
    public boolean reviewed() {
        return status != null && status == 2;
    }
}
