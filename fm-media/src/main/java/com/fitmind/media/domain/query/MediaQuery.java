package com.fitmind.media.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.media.enums.FileStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 媒资分页查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "媒资查询条件")
public class MediaQuery extends PageQuery {

    @Schema(description = "视频名称，模糊匹配", example = "深蹲")
    private String name;

    @Schema(description = "状态：1-上传中 2-已上传 3-已使用", example = "2")
    private FileStatus status;
}
