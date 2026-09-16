package com.fitmind.message.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 全员公告（<b>pull 模式</b>）。
 *
 * <p><b>★ 这张表存在的意义是"省写"</b>：给 10 万用户发一条公告，
 * 如果逐个写进 {@code user_inbox}，就是 10 万行 INSERT —— 而其中绝大多数人
 * 根本不会看这条公告。所以公告只在这里写 <b>1 行</b>，
 * 用户来查消息时才把"他上次看到之后新增的"展开进自己的信箱。</p>
 *
 * <p>{@code push_time} 承担两个职责：① 展示顺序；② 判断"哪些公告是新的"。
 * 后者在常见实现里是唯一的去重依据，于是同一毫秒发布的公告会被重复展开 ——
 * 我们的去重放在 {@code user_inbox} 的唯一索引上（精确到公告 id），
 * 不再依赖时间戳的精度。{@code push_time} 退化为纯粹的排序字段。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("public_notice")
public class PublicNotice implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 通知类型，见 {@code MessageConstants.TYPE_*} */
    private Integer type;

    private String title;

    private String content;

    /** 发布时间（用户按它判断"哪些还没看过"） */
    private LocalDateTime pushTime;

    /** 失效时间（过期公告不再展开给用户） */
    private LocalDateTime expireTime;

    private LocalDateTime createTime;
}
