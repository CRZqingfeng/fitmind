package com.fitmind.plan.utils;

import com.fitmind.plan.constants.PlanConstants;
import com.fitmind.plan.domain.po.PlanDayItem;
import com.fitmind.plan.domain.po.WorkoutSetLog;

import java.util.Collection;
import java.util.Map;

/**
 * 预计时长估算 —— 全模块**唯一**的口径实现。
 *
 * <p>为什么单独抽一个类：这个公式至少三处要用（训练日 est_minutes、计划详情"7个动作·预计43分钟"、
 * 完成训练时 session 的 duration_sec 兜底）。写三份的下场是演示时三个数字对不上，
 * 而用户会以为是你算错了，不会以为是"两套口径"。</p>
 *
 * <p><b>口径</b>（M11-2 方案 §4.2，前后端一起认）：</p>
 * <pre>
 * 单组秒数 perSet = durationSec > 0 ? durationSec : reps × 3
 * 动作项秒数 = 组数 × perSet + (组数 − 1) × 组休
 * 训练日分钟 = ceil( Σ动作项秒数 / 60 )
 * </pre>
 *
 * <p><b>为什么不把组休算成"组数 × 组休"</b>：练完最后一组人就走了，不会再歇 60 秒。
 * 多算一组休息，7 个动作的计划会虚高 7 分钟。</p>
 */
public final class PlanTimeEstimator {

    private PlanTimeEstimator() {
    }

    /**
     * 单个动作项的预计秒数。
     *
     * @param sets        组数
     * @param reps        每组次数（计时型传 0）
     * @param durationSec 计时型单组秒数（次数型传 0）
     * @param restSec     组间休息秒数
     */
    public static int itemSeconds(int sets, int reps, int durationSec, int restSec) {
        if (sets <= 0) {
            return 0;
        }
        int perSet = durationSec > 0 ? durationSec : reps * PlanConstants.SECONDS_PER_REP;
        return sets * perSet + (sets - 1) * Math.max(restSec, 0);
    }

    /** 动作项的预计秒数（PO 版本，null 安全） */
    public static int itemSeconds(PlanDayItem item) {
        return itemSeconds(
                nz(item.getSets()), nz(item.getReps()), nz(item.getDurationSec()), nz(item.getRestSec()));
    }

    /**
     * 训练日预计分钟：向上取整，且**至少 1 分钟**。
     *
     * <p>为什么至少 1：只有一个 30 秒拉伸的训练日，ceil(30/60)=1 没问题；
     * 但如果以后出现 0 秒的项，返回 0 会让前端显示"预计 0 分钟"，看起来像没算。</p>
     */
    public static int estimateMinutes(Collection<PlanDayItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (PlanDayItem item : items) {
            total += itemSeconds(item);
        }
        if (total <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(total / 60.0));
    }

    /**
     * 按"已完成的组"估算这次训练至少练了多少秒 —— 给 duration_sec 做兜底。
     *
     * <p>为什么需要它：验收脚本 5 秒就跑完一次训练，{@code now - start_time} = 5 秒，
     * 甚至可能 0 秒。而首页要显示"今日训练 X 分钟"，0 分钟看着像功能坏了。
     * 用 max(实际, 估算) 之后，秒级流程也会得到一个合理的正数。</p>
     *
     * @param logs    本次训练已记录的组（按 itemId 分组不影响结果）
     * @param itemMap 动作项 id → 动作项（取组休用）
     */
    public static int estimateDoneSeconds(Collection<WorkoutSetLog> logs, Map<Long, PlanDayItem> itemMap) {
        if (logs == null || logs.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (WorkoutSetLog log : logs) {
            int reps = nz(log.getReps());
            int duration = nz(log.getDurationSec());
            total += duration > 0 ? duration : reps * PlanConstants.SECONDS_PER_REP;
        }
        // 每组之间至少歇一次（同一动作项的相邻组之间）
        Map<Long, Integer> restPerItem = new java.util.HashMap<>();
        for (WorkoutSetLog log : logs) {
            PlanDayItem item = itemMap.get(log.getItemId());
            if (item == null) {
                continue;
            }
            restPerItem.merge(log.getItemId(), nz(item.getRestSec()), (a, b) -> a);
        }
        for (Map.Entry<Long, Integer> entry : restPerItem.entrySet()) {
            long doneSets = logs.stream().filter(l -> entry.getKey().equals(l.getItemId())).count();
            if (doneSets > 1) {
                total += (int) (doneSets - 1) * entry.getValue();
            }
        }
        return total;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
