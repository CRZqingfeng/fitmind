package com.fitmind.message.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户信箱（用户实际看到的消息）。
 *
 * <p><b>★ 本表最重要的一行是唯一索引 {@code uk_user_source}</b>：
 * {@code (user_id, source_type, source_id)}。</p>
 *
 * <p>它解决两个问题，而这两个问题在常见实现里都存在：</p>
 * <ol>
 *   <li><b>业务通知重复</b>：MQ 是 at-least-once，重复投递就会重复写一行
 *       → 用户收到两条一模一样的"购课成功"。有了唯一索引，
 *       第二次插入直接冲突，捕获忽略即可 —— <b>幂等由数据结构保证，
 *       而不是靠"消息只会来一次"这个错误假设</b>。</li>
 *   <li><b>公告重复展开</b>：常见实现靠 {@code push_time} 精确匹配来判断
 *       "哪些公告是新的"，而 {@code LocalDateTime.now()} 在同一批操作里
 *       往往返回相同值 → 同一毫秒发布的两条公告会被重复插入。
 *       我们精确到"哪一条公告"（{@code source_id = public_notice.id}），
 *       与时间精度彻底无关。</li>
 * </ol>
 *
 * <p>为什么 {@code source_type} 是必须的：公告 id=5 和订单 id=5 会撞进同一个
 * 三元组，若不区分来源，订单通知会被"公告 5 已经通知过"顶掉。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_inbox")
public class UserInbox implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 收件人 */
    private Long userId;

    /** 通知类型，见 {@code MessageConstants.TYPE_*} */
    private Integer type;

    private String title;

    /** 通知内容（占位符已渲染） */
    private String content;

    /** 是否已读：0-未读 1-已读 */
    @TableField("is_read")
    private Integer isRead;

    /** 发送者id；空表示系统 */
    private Long publisher;

    /** 推送时间 */
    private LocalDateTime pushTime;

    /** 过期时间（过期不再展示） */
    private LocalDateTime expireTime;

    /** ★ 来源类型，见 {@code MessageConstants.SOURCE_*}（幂等键的一半） */
    private Integer sourceType;

    /** ★ 来源id：公告id / 业务id（幂等键的另一半） */
    private Long sourceId;

    private LocalDateTime createTime;
}
