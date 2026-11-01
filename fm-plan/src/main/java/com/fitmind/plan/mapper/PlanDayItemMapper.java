package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.PlanDayItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 动作项 Mapper。
 */
@Mapper
public interface PlanDayItemMapper extends BaseMapper<PlanDayItem> {

    /**
     * 删掉第 k 项之后，把后面的序号整体前移一位，保持 1..n 连续。
     *
     * <p><b>为什么必须重排</b>：唯一索引是 {@code uk_item_sort(plan_day_id, sort)}，
     * 不重排会留下 1,2,4,5 这种空洞。空洞本身不报错，但下一次插入取 max+1 会跳过，
     * 而前端的"第 N 个动作"、以及按 sort 做拖拽排序都会跟着别扭。</p>
     *
     * <p><b>为什么加 ORDER BY sort ASC</b>：重排是把 3→2、4→3、5→4 依次前移。
     * MySQL 若按乱序更新，中间态可能出现两行 sort 相同，直接撞唯一索引。
     * 升序更新保证任意时刻都没有重复值。</p>
     */
    @Update("UPDATE plan_day_item SET sort = sort - 1 "
            + "WHERE plan_day_id = #{planDayId} AND sort > #{sort} ORDER BY sort ASC")
    int shiftDownAfterDelete(@Param("planDayId") Long planDayId, @Param("sort") Integer sort);
}
