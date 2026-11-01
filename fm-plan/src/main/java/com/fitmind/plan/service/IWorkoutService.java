package com.fitmind.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.plan.domain.dto.WorkoutSetDTO;
import com.fitmind.plan.domain.dto.WorkoutStartDTO;
import com.fitmind.plan.domain.po.WorkoutSession;
import com.fitmind.plan.domain.vo.WorkoutSessionVO;

/**
 * 训练执行服务。
 */
public interface IWorkoutService extends IService<WorkoutSession> {

    /** 开始今日训练（重复调用返回已存在的 session），返回 sessionId */
    Long start(WorkoutStartDTO dto);

    /** 执行页数据：训练日 + 动作项 + 已完成的组 */
    WorkoutSessionVO querySession(Long sessionId);

    /** 记录一组（同一 item + setNo 覆盖，天然幂等） */
    void logSet(Long sessionId, WorkoutSetDTO dto);

    /**
     * 取消打勾（删一组）：删掉第 setNo 组，并把同一动作项后面的组号前移，保持 1..n。
     *
     * <p>★ 只有"进行中"的训练能改；已完成的训练返回 400 —— 否则 total_volume /
     * duration_sec 会和组记录对不上。</p>
     */
    void removeSet(Long sessionId, Long itemId, Integer setNo);

    /** 完成训练：写 end_time / duration_sec / total_volume / status=1 */
    void finish(Long sessionId);
}
