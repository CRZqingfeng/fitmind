package com.fitmind.data.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 事件去重记录。
 *
 * <p><b>★ 这是数据中心最重要的一张表。</b></p>
 *
 * <p>MQ 是 at-least-once：网络抖动、消费者重启、手动重投都会让同一条消息
 * 被处理两次。而统计的动作是 <b>INCR（累加）</b> ——
 * 累加对重复投递<b>没有任何抵抗力</b>，处理两次就是两倍的成交额。</p>
 *
 * <p>所以流程必须是「<b>先插这条记录，插成功了才去计数</b>」：
 * 唯一索引 {@code uk_event_biz} 撞了就说明处理过，直接 return。</p>
 *
 * <p>对照一下项目里另外两种幂等语义，能看出为什么这里必须用唯一索引：</p>
 * <ul>
 *   <li>积分（M3-2）：累加 → 唯一索引 {@code uk_user_type_biz}</li>
 *   <li>点赞数（M3-3a）：覆盖 → 天然幂等，不需要索引</li>
 *   <li>站内信（M4-4）：插入 → 唯一索引 {@code uk_user_source}</li>
 * </ul>
 *
 * <p>规律很清晰：<b>只要是"累加"或"新增"，就必须有唯一索引兜底</b>。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("data_event_record")
public class DataEventRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 事件类型（见 DataEventType） */
    private String eventType;

    /** 业务唯一标识（同一事件类型内不重复） */
    private String bizId;

    /** 关联用户（可为空） */
    private Long userId;

    /**
     * 归属日期 yyyyMMdd。
     *
     * <p>★ 按<b>业务发生时间</b>而不是消息到达时间：
     * 体测消息带 {@code assessTime}，退款也可能隔天才回调，
     * 用到达时间会把昨天的行为记到今天头上。</p>
     */
    private Integer statDate;

    private LocalDateTime createTime;
}
