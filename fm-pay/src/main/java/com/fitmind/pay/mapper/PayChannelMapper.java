package com.fitmind.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.pay.domain.po.PayChannel;
import org.apache.ibatis.annotations.Mapper;

/**
 * 支付渠道 Mapper。
 */
@Mapper
public interface PayChannelMapper extends BaseMapper<PayChannel> {
}
