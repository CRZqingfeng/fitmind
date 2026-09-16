package com.fitmind.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.course.domain.po.CourseCatalogue;
import org.apache.ibatis.annotations.Mapper;

/**
 * 训练目录（正式表）Mapper。
 */
@Mapper
public interface CourseCatalogueMapper extends BaseMapper<CourseCatalogue> {
}
