package com.fitmind.media.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 物理对象表 —— 秒传与引用计数的载体。
 *
 * <p><b>为什么不能只靠 file 表：</b>秒传的本质是「同一份字节被多条业务记录引用」。
 * 如果两条 file 记录共用一个 id，A 删掉它，B 的文件就凭空消失；
 * 如果两条 file 记录共用一个 key 又不建这张表，就没人知道"这份对象还有几个人在用"，
 * 删除时要么删早了（别人挂了），要么永远不删（存储只增不减）。</p>
 *
 * <p><b>唯一键的两个作用</b>（都不是摆设）：</p>
 * <ul>
 *   <li>{@code uk_biz_hash}：秒传的判定键。★ 刻意<b>不含</b> {@code file_hash} 单列唯一 ——
 *       跨业务前缀复用会让"私有前缀"的对象披上"公开前缀"的身份（见方案 D3）。</li>
 *   <li>{@code uk_object_key}：两个客户端同时 complete 同一文件时的最后一道闸，
 *       冲突就说明别人已经建好了，退化成 {@code ref_count + 1} 即可。</li>
 * </ul>
 */
@Data
@Accessors(chain = true)
@TableName("file_object")
public class FileObject implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键，物理对象 id */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 文件内容 SHA-256（小写 hex） */
    private String fileHash;

    /** 业务类型，决定对象前缀与可访问策略 */
    private String bizType;

    /** 对象存储中的 key（由 hash 派生，可预测） */
    private String objectKey;

    /** 文件大小，单位字节 */
    private Long size;

    /** 被多少条 file 记录引用，归零才删物理对象 */
    private Integer refCount;

    /** 首个上传者 */
    private Long creater;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
