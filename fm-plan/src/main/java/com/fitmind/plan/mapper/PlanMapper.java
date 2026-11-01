package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.Plan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 计划 Mapper。
 */
@Mapper
public interface PlanMapper extends BaseMapper<Plan> {

    /**
     * 热度 +1。
     *
     * <p>为什么用 SQL 自增而不是「查出来 +1 再 update」：后者是读改写，两个用户同时执行
     * 同一个官方计划会丢掉一次（ref_count 只是热度，丢了不影响业务，但会让人以为"没人练"）。</p>
     */
    @Update("UPDATE plan SET ref_count = ref_count + 1 WHERE id = #{planId}")
    int increaseRefCount(@Param("planId") Long planId);
}
