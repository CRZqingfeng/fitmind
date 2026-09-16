package com.fitmind.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.course.domain.po.Category;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分类 Mapper。
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
