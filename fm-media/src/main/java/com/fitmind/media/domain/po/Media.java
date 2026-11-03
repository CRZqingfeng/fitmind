package com.fitmind.media.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fitmind.media.enums.FileStatus;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 媒资表，训练视频。
 *
 * <p>上传流程决定了 {@code status} 的流转：</p>
 * <ol>
 *   <li>申请上传签名时插入记录，status = 1（上传中）</li>
 *   <li>前端直传 MinIO 后调用确认接口，服务端核实对象存在，status = 2（已上传）</li>
 * </ol>
 * <p>只做第 1 步不确认的记录就是「僵尸数据」（前端拿了 URL 但没传），
 * 目前提供按 status 查询的能力，定时清理放到 M3（XXL-JOB 上线后）。</p>
 */
@Data
@Accessors(chain = true)
@TableName("media")
public class Media implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键，媒资 id */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 对象存储 key，如 {@code media/2026/09/uuid.mp4} */
    private String fileId;

    /**
     * 引用的物理对象 id（M12-1 秒传；老数据为 null）。
     *
     * <p>非空说明这条媒资与别人共享同一份视频字节，删除时必须走引用计数。</p>
     */
    private Long objectId;

    /** 视频原始文件名 */
    private String filename;

    /** 不带签名的播放地址 */
    private String mediaUrl;

    /** 视频封面地址 */
    private String coverUrl;

    /** 视频时长，单位秒；由前端读取 video 标签后提交 */
    private Integer duration;

    /** 文件大小，单位字节；以 MinIO 返回的实际大小为准 */
    private Long size;

    /** 状态：1-上传中 2-已上传 3-已使用 */
    private FileStatus status;

    /** 被课程引用次数，>0 时禁止删除（M2-2 课程模块维护） */
    private Integer useTimes;

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
