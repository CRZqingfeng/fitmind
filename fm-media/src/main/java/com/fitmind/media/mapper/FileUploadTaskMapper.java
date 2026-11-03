package com.fitmind.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.media.domain.po.FileUploadTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 上传任务 Mapper（查询条件都在 Service 里用 LambdaQueryWrapper 拼，无需自定义 SQL）。
 */
@Mapper
public interface FileUploadTaskMapper extends BaseMapper<FileUploadTask> {
}
