package com.fitmind.learning.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 积分明细。
 *
 * <p>{@code (user_id, type, biz_id)} 唯一索引是<b>积分幂等</b>的落点：
 * MQ 重复投递时第二次插入被数据库挡住，明细不多行、榜单也不加分。</p>
 */
@Data
@Accessors(chain = true)
@TableName("points_record")
public class PointsRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 积分类型：1-学习 2-签到 3-问答 4-笔记 */
    private Integer type;

    /** 本次积分（可能被每日上限截断） */
    private Integer points;

    /** 业务唯一标识（幂等用）：学习={lessonId}:{sectionId}、签到=yyyyMMdd */
    private String bizId;

    private LocalDateTime createTime;
}
