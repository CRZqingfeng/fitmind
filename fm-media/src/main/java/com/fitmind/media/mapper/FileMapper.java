package com.fitmind.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.fitmind.media.domain.po.File;

/**
 * 文件表 Mapper。
 */
@Mapper
public interface FileMapper extends BaseMapper<File> {
}
