package com.fitmind.remark.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.remark.domain.po.LikedRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 点赞明细 Mapper。
 */
@Mapper
public interface LikedRecordMapper extends BaseMapper<LikedRecord> {
}
