package com.fitmind.media.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 分片上传进度（一片一行）。
 *
 * <p>{@code uk_task_part} 是幂等的来源：同一片重复上传不新增行，走 upsert 覆盖。
 * 没有这个唯一键，"重传一次就多一行"，最终 {@code count(*)} 会把进度算错 ——
 * 而进度算错的直接后果是"明明还缺片，却允许合并"。</p>
 */
@Data
@Accessors(chain = true)
@TableName("file_upload_part")
public class FileUploadPart implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属任务 id */
    private Long taskId;

    /** 分片序号，从 1 开始 */
    private Integer partNumber;

    /** 该片在对象存储中的 ETag（当前实现用于审计；合并由 compose 完成，不参与拼装） */
    private String etag;

    /** 该片大小 */
    private Long size;

    private LocalDateTime createTime;
}
