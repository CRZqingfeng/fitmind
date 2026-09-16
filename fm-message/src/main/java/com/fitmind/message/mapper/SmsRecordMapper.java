package com.fitmind.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.message.domain.po.SmsRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 短信下发记录 Mapper。
 */
@Mapper
public interface SmsRecordMapper extends BaseMapper<SmsRecord> {
}
