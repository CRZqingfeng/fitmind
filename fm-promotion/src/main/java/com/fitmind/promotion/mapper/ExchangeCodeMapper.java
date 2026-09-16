package com.fitmind.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.promotion.domain.po.ExchangeCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExchangeCodeMapper extends BaseMapper<ExchangeCode> {

    /**
     * 按序列号查券id（兑换码 → 属于哪张券）。
     *
     * <p>注意主键就是序列号，所以这是一次主键查询。</p>
     */
    @Select("SELECT exchange_target_id FROM exchange_code WHERE id = #{serialNum}")
    Long queryTargetId(Long serialNum);
}
