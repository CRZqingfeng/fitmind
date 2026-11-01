package com.fitmind.plan.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.plan.constants.PlanConstants;
import com.fitmind.plan.constants.PlanErrorInfo;
import com.fitmind.plan.domain.dto.WorkoutSetDTO;
import com.fitmind.plan.domain.dto.WorkoutStartDTO;
import com.fitmind.plan.domain.po.Exercise;
import com.fitmind.plan.domain.po.Plan;
import com.fitmind.plan.domain.po.PlanDay;
import com.fitmind.plan.domain.po.PlanDayItem;
import com.fitmind.plan.domain.po.UserPlan;
import com.fitmind.plan.domain.po.WorkoutSession;
import com.fitmind.plan.domain.po.WorkoutSetLog;
import com.fitmind.plan.domain.vo.WorkoutItemVO;
import com.fitmind.plan.domain.vo.WorkoutSessionVO;
import com.fitmind.plan.domain.vo.WorkoutSetVO;
import com.fitmind.plan.mapper.ExerciseMapper;
import com.fitmind.plan.mapper.PlanDayItemMapper;
import com.fitmind.plan.mapper.PlanDayMapper;
import com.fitmind.plan.mapper.PlanMapper;
import com.fitmind.plan.mapper.UserPlanMapper;
import com.fitmind.plan.mapper.WorkoutSessionMapper;
import com.fitmind.plan.mapper.WorkoutSetLogMapper;
import com.fitmind.plan.service.IWorkoutService;
import com.fitmind.plan.utils.AssetUrls;
import com.fitmind.plan.utils.LoginUser;
import com.fitmind.plan.utils.PlanTimeEstimator;
import com.fitmind.plan.utils.PlanVoAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 训练执行实现。
 *
 * <p><b>本类最容易写错的地方</b>：
 * <ol>
 *   <li>{@link #start} 的"已存在就复用"：不能先 select 再 insert（并发窗口），
 *       必须靠 {@code uk_user_day_plan} 唯一索引 + 捕获 {@link DuplicateKeyException}。</li>
 *   <li>{@link #finish} 的 duration_sec：只取 {@code now - start_time} 会得到 0 秒
 *       （自动化脚本 5 秒就跑完），必须和"已完成的组估算秒数"取大。</li>
 *   <li>{@link #logSet} 的默认值：不传重量/次数时用计划里的值，
 *       否则徒手动作和历史习惯都会记成 0 次。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkoutServiceImpl extends ServiceImpl<WorkoutSessionMapper, WorkoutSession>
        implements IWorkoutService {

    private final PlanDayMapper planDayMapper;
    private final PlanDayItemMapper planDayItemMapper;
    private final ExerciseMapper exerciseMapper;
    private final PlanMapper planMapper;
    private final UserPlanMapper userPlanMapper;
    private final WorkoutSetLogMapper setLogMapper;

    @Override
    public Long start(WorkoutStartDTO dto) {
        Long userId = LoginUser.id();
        LocalDate today = LocalDate.now();

        PlanDay day = planDayMapper.selectById(dto.getPlanDayId());
        if (day == null) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.DAY_NOT_EXISTS);
        }

        UserPlan userPlan = userPlanMapper.selectOne(Wrappers.<UserPlan>lambdaQuery()
                .eq(UserPlan::getUserId, userId)
                .eq(UserPlan::getPlanId, day.getPlanId())
                .last("limit 1"));
        if (userPlan == null) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.USER_PLAN_NOT_EXISTS);
        }
        if (!Objects.equals(userPlan.getStatus(), PlanConstants.USER_PLAN_RUNNING)) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.USER_PLAN_NOT_RUNNING);
        }

        // 今天这一练已经开过了 → 直接复用（同一训练日同一天只允许一条 session）
        WorkoutSession exist = findTodaySession(userId, today, day.getId());
        if (exist != null) {
            log.info("复用今天的训练：userId={}, planDayId={}, sessionId={}", userId, day.getId(), exist.getId());
            return exist.getId();
        }

        WorkoutSession session = new WorkoutSession()
                .setUserId(userId)
                .setUserPlanId(userPlan.getId())
                .setPlanDayId(day.getId())
                .setSessionDate(today)
                .setStartTime(LocalDateTime.now())
                .setDurationSec(0)
                .setKcal(0)
                .setTotalVolume(BigDecimal.ZERO)
                .setStatus(PlanConstants.SESSION_RUNNING);
        try {
            save(session);
        } catch (DuplicateKeyException e) {
            // 连点两下 / 两个标签页同时开：另一个请求已经插进去了，这里退回"复用"，不报 500
            WorkoutSession again = findTodaySession(userId, today, day.getId());
            if (again == null) {
                throw e;
            }
            return again.getId();
        }
        log.info("开始训练：userId={}, planDayId={}, sessionId={}", userId, day.getId(), session.getId());
        return session.getId();
    }

    @Override
    public WorkoutSessionVO querySession(Long sessionId) {
        Long userId = LoginUser.id();

        WorkoutSession session = getById(sessionId);
        if (session == null) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.SESSION_NOT_EXISTS);
        }
        if (!Objects.equals(session.getUserId(), userId)) {
            throw new BizIllegalException(403, PlanErrorInfo.Msg.SESSION_NOT_OWNER);
        }

        PlanDay day = planDayMapper.selectById(session.getPlanDayId());
        Plan plan = day == null ? null : planMapper.selectById(day.getPlanId());

        List<PlanDayItem> items = day == null ? List.of()
                : planDayItemMapper.selectList(Wrappers.<PlanDayItem>lambdaQuery()
                        .eq(PlanDayItem::getPlanDayId, day.getId())
                        .orderByAsc(PlanDayItem::getSort));

        List<Long> exerciseIds = items.stream().map(PlanDayItem::getExerciseId).distinct().toList();
        Map<Long, Exercise> exerciseMap = exerciseIds.isEmpty()
                ? Collections.emptyMap()
                : exerciseMapper.selectBatchIds(exerciseIds).stream()
                        .collect(Collectors.toMap(Exercise::getId, Function.identity(), (a, b) -> a));

        // 已完成的组：中途刷新页面靠这一份恢复，不能只在本地记
        Map<Long, List<WorkoutSetLog>> logMap = setLogMapper.selectList(Wrappers.<WorkoutSetLog>lambdaQuery()
                        .eq(WorkoutSetLog::getSessionId, sessionId)
                        .orderByAsc(WorkoutSetLog::getSetNo))
                .stream()
                .collect(Collectors.groupingBy(WorkoutSetLog::getItemId));

        List<WorkoutItemVO> itemVos = new ArrayList<>(items.size());
        int totalSets = 0;
        int doneSets = 0;
        for (PlanDayItem item : items) {
            List<WorkoutSetLog> logs = logMap.getOrDefault(item.getId(), List.of());
            WorkoutItemVO vo = new WorkoutItemVO();
            Exercise exercise = exerciseMap.get(item.getExerciseId());
            vo.setItemId(item.getId());
            vo.setExerciseId(item.getExerciseId());
            vo.setExerciseName(exercise == null ? "动作已下架" : exercise.getName());
            vo.setImageUrl(exercise == null ? null : AssetUrls.toUrl(exercise.getImageUrl()));
            vo.setCategory(exercise == null ? null : exercise.getCategory());
            vo.setGroupType(item.getGroupType());
            vo.setGroupTypeLabel(PlanConstants.groupTypeLabel(item.getGroupType()));
            vo.setSets(item.getSets());
            vo.setReps(item.getReps());
            vo.setDurationSec(item.getDurationSec());
            vo.setWeightKg(item.getWeightKg());
            vo.setRestSec(item.getRestSec());
            vo.setPrescription(PlanVoAssembler.prescription(item));
            vo.setDoneSets(logs.size());
            vo.setSetLogs(logs.stream().map(this::toSetVo).toList());
            itemVos.add(vo);

            totalSets += nz(item.getSets());
            doneSets += logs.size();
        }

        WorkoutSessionVO vo = new WorkoutSessionVO();
        vo.setSessionId(session.getId());
        vo.setSessionDate(session.getSessionDate());
        vo.setStatus(session.getStatus());
        vo.setStatusLabel(sessionStatusLabel(session.getStatus()));
        vo.setPlanId(plan == null ? null : plan.getId());
        vo.setPlanTitle(plan == null ? null : plan.getTitle());
        vo.setPlanDayId(day == null ? null : day.getId());
        vo.setPlanDayName(day == null ? null : day.getName());
        vo.setStartTime(session.getStartTime());
        vo.setEndTime(session.getEndTime());
        vo.setDurationSec(session.getDurationSec());
        vo.setKcal(session.getKcal());
        vo.setTotalVolume(session.getTotalVolume());
        vo.setDoneSets(doneSets);
        vo.setTotalSets(totalSets);
        vo.setItems(itemVos);
        return vo;
    }

    @Override
    public void logSet(Long sessionId, WorkoutSetDTO dto) {
        Long userId = LoginUser.id();

        WorkoutSession session = getById(sessionId);
        if (session == null) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.SESSION_NOT_EXISTS);
        }
        if (!Objects.equals(session.getUserId(), userId)) {
            throw new BizIllegalException(403, PlanErrorInfo.Msg.SESSION_NOT_OWNER);
        }
        if (!Objects.equals(session.getStatus(), PlanConstants.SESSION_RUNNING)) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.SESSION_FINISHED);
        }

        PlanDayItem item = planDayItemMapper.selectById(dto.getItemId());
        if (item == null) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.ITEM_NOT_EXISTS);
        }
        // ★ 动作项必须属于这次训练的训练日，否则能把别的计划的动作记进来
        if (!Objects.equals(item.getPlanDayId(), session.getPlanDayId())) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.SET_ITEM_ILLEGAL);
        }

        WorkoutSetLog log = new WorkoutSetLog()
                .setSessionId(sessionId)
                .setItemId(item.getId())
                .setExerciseId(item.getExerciseId())
                .setSetNo(dto.getSetNo())
                // 不填就沿用计划里的建议值：用户"直接打勾"是最常见的操作
                .setWeightKg(dto.getWeightKg() != null ? dto.getWeightKg() : item.getWeightKg())
                .setReps(dto.getReps() != null ? dto.getReps() : nz(item.getReps()))
                .setDurationSec(dto.getDurationSec() != null ? dto.getDurationSec() : nz(item.getDurationSec()))
                .setDone(1);
        // 同一 item + setNo 走 ON DUPLICATE KEY UPDATE：重复点打勾是"覆盖"，不是"多做一组"
        setLogMapper.upsert(log);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void finish(Long sessionId) {
        Long userId = LoginUser.id();

        WorkoutSession session = getById(sessionId);
        if (session == null) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.SESSION_NOT_EXISTS);
        }
        if (!Objects.equals(session.getUserId(), userId)) {
            throw new BizIllegalException(403, PlanErrorInfo.Msg.SESSION_NOT_OWNER);
        }
        // ★ 已完成就静默返回：连点两次"完成训练"不该报错，也不该把 duration 覆盖成 0
        if (Objects.equals(session.getStatus(), PlanConstants.SESSION_FINISHED)) {
            log.info("训练已完成，幂等返回：sessionId={}", sessionId);
            return;
        }
        if (!Objects.equals(session.getStatus(), PlanConstants.SESSION_RUNNING)) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.SESSION_FINISHED);
        }

        List<WorkoutSetLog> logs = setLogMapper.selectList(Wrappers.<WorkoutSetLog>lambdaQuery()
                .eq(WorkoutSetLog::getSessionId, sessionId));
        List<PlanDayItem> items = planDayItemMapper.selectList(Wrappers.<PlanDayItem>lambdaQuery()
                .eq(PlanDayItem::getPlanDayId, session.getPlanDayId()));
        Map<Long, PlanDayItem> itemMap = items.stream()
                .collect(Collectors.toMap(PlanDayItem::getId, Function.identity(), (a, b) -> a));

        // ★ duration_sec = max(实际耗时, 已完成组的估算秒数)，至少 1 秒。
        //   只取实际耗时的话，脚本 5 秒跑完 + 首页按分钟四舍五入 → 显示"今天练了 0 分钟"。
        long elapsed = session.getStartTime() == null
                ? 0 : Duration.between(session.getStartTime(), LocalDateTime.now()).getSeconds();
        int estimated = PlanTimeEstimator.estimateDoneSeconds(logs, itemMap);
        int durationSec = (int) Math.max(Math.max(elapsed, estimated), 1);

        BigDecimal volume = logs.stream()
                .filter(l -> l.getWeightKg() != null && l.getReps() != null && l.getReps() > 0)
                .map(l -> l.getWeightKg().multiply(BigDecimal.valueOf(l.getReps())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        updateById(new WorkoutSession()
                .setId(sessionId)
                .setEndTime(LocalDateTime.now())
                .setDurationSec(durationSec)
                // kcal 先留 0：动作数据集没有热量字段，凭空估一个数就是编造（P1 接体重 + MET 再算）
                .setKcal(0)
                .setTotalVolume(volume)
                .setStatus(PlanConstants.SESSION_FINISHED));

        // 练完这一练：① 把"当前练到第几练"往后挪一格 ② 若计划里**所有**训练日都练过，
        // 把 user_plan 置成"已完成"。
        // ★ 为什么必须在这里判"全部练完"，而不是靠前端数一数：
        //   前端的"完成度"随时可以被刷新、被别的标签页改掉；而 status 一旦写进库，
        //   首页的状态徽标、"我的计划"的分组就都有了唯一事实来源。
        PlanDay day = planDayMapper.selectById(session.getPlanDayId());
        if (day != null && session.getUserPlanId() != null) {
            UserPlan userPlan = userPlanMapper.selectById(session.getUserPlanId());
            if (userPlan != null && Objects.equals(userPlan.getStatus(), PlanConstants.USER_PLAN_RUNNING)) {
                List<PlanDay> allDays = planDayMapper.selectList(Wrappers.<PlanDay>lambdaQuery()
                        .eq(PlanDay::getPlanId, day.getPlanId()));
                // 这个计划下"已练完"的训练日集合（按 user_plan 归集，不看别的计划）
                Set<Long> doneDayIds = list(Wrappers.<WorkoutSession>lambdaQuery()
                        .eq(WorkoutSession::getUserPlanId, userPlan.getId())
                        .eq(WorkoutSession::getStatus, PlanConstants.SESSION_FINISHED))
                        .stream()
                        .map(WorkoutSession::getPlanDayId)
                        .collect(Collectors.toSet());
                boolean allDone = !allDays.isEmpty()
                        && allDays.stream().allMatch(d -> doneDayIds.contains(d.getId()));

                UserPlan patch = new UserPlan().setId(userPlan.getId());
                if (allDone) {
                    patch.setStatus(PlanConstants.USER_PLAN_FINISHED);
                    log.info("计划里所有训练日都练完了，user_plan 置为已完成：userPlanId={}, planId={}, 训练日={}",
                            userPlan.getId(), day.getPlanId(), allDays.size());
                } else if (day.getDayIndex() != null) {
                    int next = (int) Math.min(Math.max(allDays.size(), 1), day.getDayIndex() + 1);
                    patch.setCurrentDayIndex(next);
                }
                userPlanMapper.updateById(patch);
            }
        }
        log.info("完成训练：sessionId={}, durationSec={}（实际{}秒 / 估算{}秒）, volume={}, 组数={}",
                sessionId, durationSec, elapsed, estimated, volume, logs.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeSet(Long sessionId, Long itemId, Integer setNo) {
        Long userId = LoginUser.id();

        WorkoutSession session = getById(sessionId);
        if (session == null) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.SESSION_NOT_EXISTS);
        }
        if (!Objects.equals(session.getUserId(), userId)) {
            throw new BizIllegalException(403, PlanErrorInfo.Msg.SESSION_NOT_OWNER);
        }
        // 已完成的训练不允许再改记录：否则 total_volume / duration_sec 会和组数对不上，
        // 而这两个值已经进了"累计训练分钟"这类统计口径里
        if (!Objects.equals(session.getStatus(), PlanConstants.SESSION_RUNNING)) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.SESSION_FINISHED);
        }
        if (setNo == null || setNo < 1) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.SET_NO_ILLEGAL);
        }

        // ★ 定位必须是三元组 (session, item, setNo)：只按 setNo 删，
        //   会把"动作A的第2组"和"动作B的第2组"当成同一行
        WorkoutSetLog setLog = setLogMapper.selectOne(Wrappers.<WorkoutSetLog>lambdaQuery()
                .eq(WorkoutSetLog::getSessionId, sessionId)
                .eq(WorkoutSetLog::getItemId, itemId)
                .eq(WorkoutSetLog::getSetNo, setNo)
                .last("limit 1"));
        if (setLog == null) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.SET_NOT_EXISTS);
        }
        setLogMapper.deleteById(setLog.getId());
        // 删了第 k 组，后面的组号整体前移，保持 1..n —— 不然执行页会显示"第1组 / 第3组"
        setLogMapper.shiftDownAfterDelete(sessionId, itemId, setNo);
        log.info("取消打勾：sessionId={}, itemId={}, setNo={}", sessionId, itemId, setNo);
    }

    // ==================== 内部 ====================

    private WorkoutSession findTodaySession(Long userId, LocalDate date, Long planDayId) {
        return getOne(Wrappers.<WorkoutSession>lambdaQuery()
                .eq(WorkoutSession::getUserId, userId)
                .eq(WorkoutSession::getSessionDate, date)
                .eq(WorkoutSession::getPlanDayId, planDayId)
                .last("limit 1"), false);
    }

    private WorkoutSetVO toSetVo(WorkoutSetLog log) {
        WorkoutSetVO vo = new WorkoutSetVO();
        vo.setSetNo(log.getSetNo());
        vo.setWeightKg(log.getWeightKg());
        vo.setReps(log.getReps());
        vo.setDurationSec(log.getDurationSec());
        vo.setDone(log.getDone());
        return vo;
    }

    private String sessionStatusLabel(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case PlanConstants.SESSION_RUNNING -> "进行中";
            case PlanConstants.SESSION_FINISHED -> "已完成";
            case PlanConstants.SESSION_GIVE_UP -> "已放弃";
            default -> "";
        };
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
