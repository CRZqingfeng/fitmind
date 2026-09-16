package com.fitmind.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.fitmind.media.domain.po.Media;

/**
 * 媒资表 Mapper。
 */
@Mapper
public interface MediaMapper extends BaseMapper<Media> {
}
