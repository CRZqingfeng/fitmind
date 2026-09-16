package com.fitmind.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.message.domain.po.NoticeTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 通知模板 Mapper。
 */
@Mapper
public interface NoticeTemplateMapper extends BaseMapper<NoticeTemplate> {

    /**
     * 按代号查模板 —— <b>业务代码只用这一种查法</b>。
     *
     * <p>不用 id：id 是自增的，开发/测试/生产环境的数字各不相同，
     * 业务代码里写 id 等于把环境写死。代号（{@code purchase-success}）
     * 是跨环境稳定的。</p>
     */
    @Select("SELECT * FROM notice_template WHERE code = #{code}")
    NoticeTemplate getByCode(@Param("code") String code);
}
