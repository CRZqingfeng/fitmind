package com.fitmind.plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.plan.domain.po.WorkoutSetLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 每组完成记录 Mapper。
 */
@Mapper
public interface WorkoutSetLogMapper extends BaseMapper<WorkoutSetLog> {

    /**
     * 记录一组（幂等）：同一 session + item + setNo 存在则覆盖。
     *
     * <p>「覆盖」而不是「累加」是数据语义决定的：用户点第 3 组两次，
     * 意思始终是"第 3 组我做了 8 次"，不是"我做了 16 次"。
     * 所以唯一索引 + ON DUPLICATE KEY UPDATE 就够了，不需要分布式锁。</p>
     *
     * <p>★ 注意 {@code item_id} 与 {@code exercise_id} 都要写：
     * error 就在于只写 item_id —— 后面按动作统计容量时得回查 plan_day_item，
     * 而计划被改过之后回查出来的就不是当时的动作了。</p>
     */
    @Insert("INSERT INTO workout_set_log (session_id, item_id, exercise_id, set_no, weight_kg, reps, duration_sec, done) "
            + "VALUES (#{log.sessionId}, #{log.itemId}, #{log.exerciseId}, #{log.setNo}, "
            + "#{log.weightKg}, #{log.reps}, #{log.durationSec}, #{log.done}) AS new "
            + "ON DUPLICATE KEY UPDATE weight_kg = new.weight_kg, reps = new.reps, "
            + "duration_sec = new.duration_sec, done = new.done")
    int upsert(@Param("log") WorkoutSetLog log);

    /**
     * 取消第 k 组之后，把同一动作项后面几组的组号前移一位，保持 1..n 连续。
     *
     * <p><b>为什么必须重排</b>：与 {@code plan_day_item} 同理 —— 唯一索引是
     * {@code uk_session_item_set(session_id, item_id, set_no)}，不重排会留下 1,3 这种空洞。
     * 空洞本身不报错，但执行页的"第 N 组"和 {@code doneSets} 计数会开始别扭：
     * 用户看到"已完成 2 组"，界面上却写着"第 1 组 / 第 3 组"。</p>
     *
     * <p><b>为什么必须按 set_no 升序</b>：3→2、4→3 依次前移；乱序更新中间态会出现
     * 两行同组号，直接撞唯一索引（与 {@code PlanDayItemMapper.shiftDownAfterDelete} 同一个坑）。</p>
     */
    @Update("UPDATE workout_set_log SET set_no = set_no - 1 "
            + "WHERE session_id = #{sessionId} AND item_id = #{itemId} AND set_no > #{setNo} "
            + "ORDER BY set_no ASC")
    int shiftDownAfterDelete(@Param("sessionId") Long sessionId,
                             @Param("itemId") Long itemId,
                             @Param("setNo") Integer setNo);
}
