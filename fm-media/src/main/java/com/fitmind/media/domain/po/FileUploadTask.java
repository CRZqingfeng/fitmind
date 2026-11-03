package com.fitmind.media.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fitmind.media.enums.UploadStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分片上传任务。
 *
 * <p><b>断点续传的本质：状态不在内存里。</b>服务重启、用户刷新页面、换一台设备，
 * 只要还能从这张表里按 (hash, bizType, size, creater) 找回同一条任务，
 * 就能只补传缺的分片。所以这张表是"恢复"的唯一入口，不是缓存。</p>
 *
 * <p>★ {@code uploadedChunks / uploadedBytes} 两个字段是<b>快照</b>，不是真相源：
 * 它们由 file_upload_part 重新统计得来（重复传同一片不会算两次）。
 * 真正合并前的最终校验，会去对象存储列一遍分片对象（见 Service 的 complete）。</p>
 */
@Data
@Accessors(chain = true)
@TableName("file_upload_task")
public class FileUploadTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 文件内容 SHA-256 */
    private String fileHash;

    /** 业务类型 */
    private String bizType;

    /** 产出类型：file-通用文件 / media-视频媒资 */
    private String target;

    /** 原始文件名 */
    private String filename;

    /** 文件总大小 */
    private Long size;

    /** 分片大小（字节，≥5MiB） */
    private Integer chunkSize;

    /** 分片总数 */
    private Integer chunkTotal;

    /** 目标对象 key（由 hash 派生，重传落同一个 key） */
    private String objectKey;

    /** 状态：1-上传中 2-已完成 3-已取消 */
    private UploadStatus status;

    /** 已成功上传的分片数（快照） */
    private Integer uploadedChunks;

    /** 已上传字节数（快照） */
    private Long uploadedBytes;

    /** 上传者 */
    private Long creater;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除 */
    private Integer deleted;
}
