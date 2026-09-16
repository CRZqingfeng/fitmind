package com.fitmind.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.trade.domain.po.TradeCart;
import org.apache.ibatis.annotations.Mapper;

/**
 * 购物车 Mapper。
 *
 * <p>全部用 MyBatis-Plus 的通用方法即可 —— 购物车没有复杂的自定义查询。</p>
 */
@Mapper
public interface TradeCartMapper extends BaseMapper<TradeCart> {
}
