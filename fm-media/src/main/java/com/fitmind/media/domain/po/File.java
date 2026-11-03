package com.fitmind.media.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fitmind.media.enums.BizType;
import com.fitmind.media.enums.FileStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文件表，图片等通用文件。
 *
 * <p>对象存储里只存 key，{@link #url} 存的是不带签名的展示地址——
 * 因为 bucket 是私有的，直接访问会 403，要真正打开图片得再换预签名地址。</p>
 */
@Data
@Accessors(chain = true)
@TableName("file")
public class File implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键，文件 id */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 对象存储中的唯一 key，如 {@code avatar/2026/09/uuid.jpg}。
     *
     * <p>⚠️ {@code key} 是 MySQL 保留字，必须用反引号包裹，否则 SQL 语法错误。</p>
     */
    @TableField("`key`")
    private String key;

    /** 上传时的原始文件名 */
    private String filename;

    /** 业务类型：头像 / 课程封面 / 动作封面 / 视频封面 / 通用 */
    private BizType bizType;

    /** 不带签名的访问地址（管理端展示用） */
    private String url;

    /**
     * 文件内容 SHA-256（M12-1 秒传用）。
     *
     * <p>老数据（走一次性上传的）为 {@code null}，不参与秒传判定 ——
     * 不要试图"回填"：回填要重新读一遍所有对象，代价远大于收益。</p>
     */
    private String fileHash;

    /**
     * 引用的物理对象 id（{@code file_object.id}）。
     *
     * <p>★ 非空表示这条记录与别人共享同一份字节，删除时必须走引用计数；
     * 为空表示老数据，key 是它独占的，可以直接删对象。</p>
     */
    private Long objectId;

    /** 文件大小，单位字节 */
    private Long size;

    /** 状态：1-上传中 2-已上传 */
    private FileStatus status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 创建者 */
    private Long creater;

    /** 更新者 */
    private Long updater;

    /** 逻辑删除：0-未删除 1-已删除 */
    private Integer deleted;
}
