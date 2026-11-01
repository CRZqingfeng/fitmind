package com.fitmind.plan.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.plan.constants.PlanConstants;
import com.fitmind.plan.constants.PlanErrorInfo;
import com.fitmind.plan.domain.po.Exercise;
import com.fitmind.plan.domain.po.Plan;
import com.fitmind.plan.domain.po.PlanDay;
import com.fitmind.plan.domain.po.PlanDayItem;
import com.fitmind.plan.domain.po.WorkoutSession;
import com.fitmind.plan.mapper.ExerciseMapper;
import com.fitmind.plan.mapper.PlanDayItemMapper;
import com.fitmind.plan.mapper.PlanDayMapper;
import com.fitmind.plan.mapper.PlanMapper;
import com.fitmind.plan.mapper.WorkoutSessionMapper;
import com.fitmind.plan.service.IPlanDayService;
import com.fitmind.plan.utils.LoginUser;
import com.fitmind.plan.utils.PlanTimeEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 训练日 / 动作项编排实现。
 *
 * <p><b>本类最容易写错的地方</b>：
 * <ol>
 *   <li>往训练日加动作时用了 {@code selectBatchIds} 就以为查全了 —— 它会把不存在的 id 直接忽略，
 *       {@code size()} 比对不上才发现"少了一个"，不比对就会出现"加了 4 个只进 3 个"。</li>
 *   <li>删除动作项后忘了重排 sort —— 唯一索引 {@code uk_item_sort} 没错，但序号会留空洞，
 *       前端"第 N 个动作"和拖拽排序全都会错位。</li>
 *   <li>加/删动作后忘算 {@code est_minutes} —— 详情页"预计43分钟"会一直停在 0。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanDayServiceImpl extends ServiceImpl<PlanDayMapper, PlanDay> implements IPlanDayService {

    private final PlanMapper planMapper;
    private final PlanDayItemMapper planDayItemMapper;
    private final ExerciseMapper exerciseMapper;
    private final WorkoutSessionMapper workoutSessionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addDays(Long planId, List<Integer> weekdays) {
        Long userId = LoginUser.id();
        Plan plan = requireOwnedPlan(planId, userId);

        List<Integer> targets = weekdays == null ? List.of()
                : weekdays.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (targets.isEmpty()) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.WEEKDAYS_EMPTY);
        }
        for (Integer weekday : targets) {
            if (weekday < 1 || weekday > 7) {
                throw new BizIllegalException(400, PlanErrorInfo.Msg.WEEKDAY_ILLEGAL);
            }
        }

        List<PlanDay> exists = list(Wrappers.<PlanDay>lambdaQuery().eq(PlanDay::getPlanId, planId));
        Set<Integer> occupied = exists.stream()
                .map(PlanDay::getWeekday)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        int nextIndex = exists.stream().mapToInt(d -> nz(d.getDayIndex())).max().orElse(0);
        int nextSort = exists.stream().mapToInt(d -> nz(d.getSort())).max().orElse(0);

        // ★ 这里为什么敢用"先查再插"：weekday 不能上唯一索引 —— 一天两练（早晚各一次）
        //   是合法训练安排，加了 uk(plan_id, weekday) 就把这条路堵死了。
        //   所以去重只能放在应用层；代价是并发下理论上仍可能重复，但后果只是多一个训练日，
        //   用户在编排页删掉即可（{@link #deleteDay}）—— 而打卡那边（session）的重复会污染数据，
        //   才必须靠唯一索引。
        List<PlanDay> toSave = new ArrayList<>();
        for (Integer weekday : targets) {
            if (occupied.contains(weekday)) {
                log.info("训练日已存在，跳过：planId={}, weekday={}", planId, weekday);
                continue;
            }
            toSave.add(new PlanDay()
                    .setPlanId(planId)
                    .setDayIndex(++nextIndex)
                    .setWeekday(weekday)
                    // ★ 默认名由后端给，前端不拼
                    .setName(PlanConstants.DEFAULT_DAY_NAME)
                    .setSort(++nextSort)
                    .setEstMinutes(0));
        }
        if (toSave.isEmpty()) {
            return;
        }
        saveBatch(toSave);
        refreshTrainDaysPerWeek(plan);
        log.info("添加训练日：planId={}, weekdays={}, 实际新增={}", planId, targets, toSave.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void renameDay(Long dayId, String name) {
        Long userId = LoginUser.id();
        PlanDay day = getById(dayId);
        if (day == null) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.DAY_NOT_EXISTS);
        }
        requireOwnedPlan(day.getPlanId(), userId);

        // 不传 / 传空串 = 保持原值（前端"点开改名又原样保存"是最常见的操作）
        if (name == null || name.isBlank()) {
            return;
        }
        String trimmed = name.trim();
        if (trimmed.length() > 32) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.DAY_NAME_TOO_LONG);
        }
        // ★ 只 update 非空字段（MP 默认 NOT_NULL 策略），不会把 sort / est_minutes 冲成 null
        updateById(new PlanDay().setId(dayId).setName(trimmed));
        log.info("训练日重命名：dayId={}, name={}", dayId, trimmed);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addItems(Long dayId, List<Long> exerciseIds) {
        Long userId = LoginUser.id();
        PlanDay day = getById(dayId);
        if (day == null) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.DAY_NOT_EXISTS);
        }
        requireOwnedPlan(day.getPlanId(), userId);

        List<Long> ids = exerciseIds == null ? List.of()
                : exerciseIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.EXERCISE_IDS_EMPTY);
        }

        // ★ selectBatchIds 会静默丢掉不存在的 id，必须比数量
        List<Exercise> exercises = exerciseMapper.selectBatchIds(ids);
        if (exercises.size() != ids.size()) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.EXERCISE_NOT_EXISTS);
        }

        List<PlanDayItem> exists = planDayItemMapper.selectList(Wrappers.<PlanDayItem>lambdaQuery()
                .eq(PlanDayItem::getPlanDayId, dayId));
        Set<Long> already = exists.stream().map(PlanDayItem::getExerciseId).collect(Collectors.toSet());
        int sort = exists.stream().mapToInt(i -> nz(i.getSort())).max().orElse(0);

        int inserted = 0;
        for (Long exerciseId : ids) {
            // 同一个动作在同一个训练日里只放一次：放两次用户看着像 8 组，实际是两个 4 组
            if (already.contains(exerciseId)) {
                continue;
            }
            PlanDayItem item = new PlanDayItem()
                    .setPlanDayId(dayId)
                    .setExerciseId(exerciseId)
                    .setSort(++sort)
                    .setGroupType(PlanConstants.GROUP_FORMAL)
                    .setSets(PlanConstants.DEFAULT_SETS)
                    .setReps(PlanConstants.DEFAULT_REPS)
                    .setDurationSec(0)
                    .setRestSec(PlanConstants.DEFAULT_REST_SEC);
            // 一次最多 50 条，逐条 insert 比给 item 单独建一个 ServiceImpl 更划算
            planDayItemMapper.insert(item);
            inserted++;
        }
        recalcEstimate(dayId);
        log.info("往训练日加动作：dayId={}, 请求={}, 实际新增={}", dayId, ids.size(), inserted);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeItem(Long dayId, Long itemId) {
        Long userId = LoginUser.id();
        PlanDayItem item = planDayItemMapper.selectById(itemId);
        if (item == null) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.ITEM_NOT_EXISTS);
        }
        // ★ 不许跨训练日删：路径里的 dayId 和 item 的真实归属必须一致，
        //   否则 /days/1/items/999 能把第 2 天的动作删掉
        if (!Objects.equals(item.getPlanDayId(), dayId)) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.ITEM_NOT_IN_DAY);
        }
        PlanDay day = getById(dayId);
        if (day == null) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.DAY_NOT_EXISTS);
        }
        requireOwnedPlan(day.getPlanId(), userId);

        planDayItemMapper.deleteById(itemId);
        // 先删再前移：留下的空洞会让"第 N 个动作"错位
        planDayItemMapper.shiftDownAfterDelete(dayId, nz(item.getSort()));
        recalcEstimate(dayId);
        log.info("删除动作项：dayId={}, itemId={}", dayId, itemId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDay(Long dayId) {
        Long userId = LoginUser.id();
        PlanDay day = getById(dayId);
        if (day == null) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.DAY_NOT_EXISTS);
        }
        Plan plan = requireOwnedPlan(day.getPlanId(), userId);

        // ★★ 练过的一天不许删：workout_session.plan_day_id 指着它。
        //    删掉之后这条训练记录就"没有归属"了 —— 首页的累计训练、详情页的训练日名、
        //    将来按部位的统计，每一处读它的地方都得加 if (day == null) 兜一次。
        //    宁可让用户多留一个训练日，也别让历史记录变成孤儿。
        long practiced = workoutSessionMapper.selectCount(Wrappers.<WorkoutSession>lambdaQuery()
                .eq(WorkoutSession::getPlanDayId, dayId));
        if (practiced > 0) {
            throw new BizIllegalException(400, String.format(
                    PlanErrorInfo.Msg.DAY_HAS_SESSION, day.getName(), practiced));
        }

        // 先删子表再删主表：反过来会留下查得到、但父行已不在的动作项
        planDayItemMapper.delete(Wrappers.<PlanDayItem>lambdaQuery()
                .eq(PlanDayItem::getPlanDayId, dayId));
        removeById(dayId);
        reshuffle(plan.getId());
        refreshTrainDaysPerWeek(plan);
        log.info("删除训练日：planId={}, dayId={}, name={}", plan.getId(), dayId, day.getName());
    }

    // ==================== 内部 ====================

    /**
     * 删掉中间某个训练日后，把剩下的 day_index / sort 重排成 1..n。
     *
     * <p><b>为什么要重排</b>：{@code plan_day} 上有唯一索引
     * {@code uk_plan_day_index(plan_id, day_index)}，而且前端会显示"第 2 练 / 第 3 练"。
     * 不重排就是 1,3,4 —— 用户会以为"第 2 练被我弄丢了"。</p>
     *
     * <p><b>为什么可以直接按升序写回、不会撞唯一索引</b>：删除后剩余序号严格递增
     * （如 1,3,4），目标序号也严格递增（1,2,3），且每条的目标值都小于等于原值，
     * 所以任意时刻已写入的值都小于待写入的值，不存在两行同号。</p>
     */
    private void reshuffle(Long planId) {
        List<PlanDay> days = list(Wrappers.<PlanDay>lambdaQuery()
                .eq(PlanDay::getPlanId, planId)
                .orderByAsc(PlanDay::getSort));
        int index = 1;
        for (PlanDay d : days) {
            if (!Objects.equals(d.getDayIndex(), index) || !Objects.equals(d.getSort(), index)) {
                updateById(new PlanDay().setId(d.getId()).setDayIndex(index).setSort(index));
            }
            index++;
        }
    }

    /** 重算预计时长（口径唯一实现见 {@link PlanTimeEstimator}） */
    private void recalcEstimate(Long dayId) {
        List<PlanDayItem> items = planDayItemMapper.selectList(Wrappers.<PlanDayItem>lambdaQuery()
                .eq(PlanDayItem::getPlanDayId, dayId));
        int minutes = PlanTimeEstimator.estimateMinutes(items);
        updateById(new PlanDay().setId(dayId).setEstMinutes(minutes));
    }

    /** 回填"一周几练"：不改的话卡片一直显示"一周0练" */
    private void refreshTrainDaysPerWeek(Plan plan) {
        long count = count(Wrappers.<PlanDay>lambdaQuery().eq(PlanDay::getPlanId, plan.getId()));
        planMapper.updateById(new Plan().setId(plan.getId()).setTrainDaysPerWeek((int) count));
    }

    /** 计划必须存在且是当前用户建的（官方计划不能改） */
    private Plan requireOwnedPlan(Long planId, Long userId) {
        Plan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.PLAN_NOT_EXISTS);
        }
        if (!Objects.equals(plan.getOwnerId(), userId)) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.PLAN_NOT_OWNER);
        }
        return plan;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
