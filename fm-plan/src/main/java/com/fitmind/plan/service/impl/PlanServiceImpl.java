package com.fitmind.plan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.plan.constants.PlanConstants;
import com.fitmind.plan.constants.PlanErrorInfo;
import com.fitmind.plan.domain.dto.PlanCreateDTO;
import com.fitmind.plan.domain.dto.PlanUpdateDTO;
import com.fitmind.plan.domain.po.Exercise;
import com.fitmind.plan.domain.po.Plan;
import com.fitmind.plan.domain.po.PlanDay;
import com.fitmind.plan.domain.po.PlanDayItem;
import com.fitmind.plan.domain.po.UserPlan;
import com.fitmind.plan.domain.po.WorkoutSession;
import com.fitmind.plan.domain.vo.MyPlanVO;
import com.fitmind.plan.domain.vo.PlanCardVO;
import com.fitmind.plan.domain.vo.PlanDayItemVO;
import com.fitmind.plan.domain.vo.PlanDayVO;
import com.fitmind.plan.domain.vo.PlanDetailVO;
import com.fitmind.plan.domain.vo.TodayWorkoutVO;
import com.fitmind.plan.mapper.ExerciseMapper;
import com.fitmind.plan.mapper.PlanDayItemMapper;
import com.fitmind.plan.mapper.PlanDayMapper;
import com.fitmind.plan.mapper.PlanMapper;
import com.fitmind.plan.mapper.UserPlanMapper;
import com.fitmind.plan.mapper.WorkoutSessionMapper;
import com.fitmind.plan.service.IPlanService;
import com.fitmind.plan.utils.LoginUser;
import com.fitmind.plan.utils.PlanJsonUtils;
import com.fitmind.plan.utils.PlanVoAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 计划实现。
 *
 * <p><b>本类最容易写错的地方</b>：
 * <ol>
 *   <li>{@link #queryDetail} 里"官方计划 vs 我的计划"的可见性 —— 自建计划别人不该看见，
 *       但官方计划必须能被所有人看到；判断写反的表现是"推荐点进去 404"。</li>
 *   <li>{@link #executePlan} 的空训练日校验 —— 不校验的话用户执行完一个空计划，
 *       首页「今日训练」显示"0 个动作"，然后点开始训练进去一片空白。</li>
 *   <li>{@link #queryToday} 三态 —— 见 {@link TodayWorkoutVO} 的注释。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanServiceImpl extends ServiceImpl<PlanMapper, Plan> implements IPlanService {

    private final PlanDayMapper planDayMapper;
    private final PlanDayItemMapper planDayItemMapper;
    private final ExerciseMapper exerciseMapper;
    private final UserPlanMapper userPlanMapper;
    private final WorkoutSessionMapper sessionMapper;

    /** 推荐列表最多给多少条：首页是横滑卡片，给多了既没人滑到底，也压慢首屏 */
    private static final int RECOMMEND_LIMIT = 20;

    @Override
    public List<PlanCardVO> queryRecommend(String goal) {
        LoginUser.id();

        LambdaQueryWrapper<Plan> wrapper = Wrappers.<Plan>lambdaQuery()
                .eq(Plan::getSource, PlanConstants.SOURCE_OFFICIAL)
                .eq(Plan::getStatus, PlanConstants.PLAN_PUBLISHED)
                .eq(hasText(goal), Plan::getGoal, goal)
                .orderByDesc(Plan::getRefCount)
                .orderByAsc(Plan::getId)
                .last("limit " + RECOMMEND_LIMIT);

        // ★ 只查 plan 一张表：卡片需要的字段全在这一行里，一条动作都不碰（避免 N+1）
        return list(wrapper).stream().map(PlanVoAssembler::toCardVo).toList();
    }

    @Override
    public List<MyPlanVO> queryMine() {
        Long userId = LoginUser.id();

        List<UserPlan> userPlans = userPlanMapper.selectList(Wrappers.<UserPlan>lambdaQuery()
                .eq(UserPlan::getUserId, userId)
                .orderByDesc(UserPlan::getUpdateTime));
        if (userPlans.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> planIds = userPlans.stream().map(UserPlan::getPlanId).distinct().toList();
        // 一次查完计划本体（不循环 getById）
        Map<Long, Plan> planMap = listByIds(planIds).stream()
                .collect(Collectors.toMap(Plan::getId, Function.identity(), (a, b) -> a));

        // 一次查完训练日数量
        Map<Long, Long> dayCountMap = planDayMapper.selectList(Wrappers.<PlanDay>lambdaQuery()
                        .in(PlanDay::getPlanId, planIds))
                .stream()
                .collect(Collectors.groupingBy(PlanDay::getPlanId, Collectors.counting()));

        // 一次查完已完成的 session，按 userPlanId 去重 planDayId
        Set<Long> userPlanIds = userPlans.stream().map(UserPlan::getId).collect(Collectors.toSet());
        Map<Long, Set<Long>> finishedDayMap = new HashMap<>();
        sessionMapper.selectList(Wrappers.<WorkoutSession>lambdaQuery()
                        .eq(WorkoutSession::getUserId, userId)
                        .eq(WorkoutSession::getStatus, PlanConstants.SESSION_FINISHED)
                        .in(WorkoutSession::getUserPlanId, userPlanIds))
                .forEach(s -> finishedDayMap
                        .computeIfAbsent(s.getUserPlanId(), k -> new HashSet<>())
                        .add(s.getPlanDayId()));

        List<MyPlanVO> result = new ArrayList<>(userPlans.size());
        for (UserPlan up : userPlans) {
            Plan plan = planMap.get(up.getPlanId());
            if (plan == null) {
                // 计划被删了但 user_plan 还在：跳过而不是抛异常，否则整个"我的计划"页打不开
                log.warn("user_plan {} 指向的 plan {} 已不存在，跳过", up.getId(), up.getPlanId());
                continue;
            }
            int dayCount = dayCountMap.getOrDefault(plan.getId(), 0L).intValue();
            int finishedDays = finishedDayMap.getOrDefault(up.getId(), Collections.emptySet()).size();

            MyPlanVO vo = new MyPlanVO();
            vo.setId(plan.getId());
            vo.setTitle(plan.getTitle());
            vo.setCoverUrl(plan.getCoverUrl());
            vo.setGoal(plan.getGoal());
            vo.setTrainDaysPerWeek(plan.getTrainDaysPerWeek());
            vo.setDayCount(dayCount);
            vo.setFinishedDays(finishedDays);
            vo.setStatus(up.getStatus());
            vo.setStatusLabel(userPlanStatusLabel(up.getStatus()));
            vo.setStartDate(up.getStartDate());
            vo.setCurrentDayIndex(up.getCurrentDayIndex());
            vo.setProgressText("已练 " + finishedDays + "/" + dayCount + " 天");
            result.add(vo);
        }
        return result;
    }

    @Override
    public PlanDetailVO queryDetail(Long planId) {
        Long userId = LoginUser.id();

        Plan plan = getById(planId);
        if (plan == null || !visibleTo(plan, userId)) {
            // 别人的自建计划一律当"不存在"，不泄露"这个 id 确实有东西"
            throw new BizIllegalException(400, PlanErrorInfo.Msg.PLAN_NOT_EXISTS);
        }

        List<PlanDay> days = planDayMapper.selectList(Wrappers.<PlanDay>lambdaQuery()
                .eq(PlanDay::getPlanId, planId)
                .orderByAsc(PlanDay::getSort));

        List<PlanDayVO> dayVos = assembleDays(days);

        UserPlan userPlan = userPlanMapper.selectOne(Wrappers.<UserPlan>lambdaQuery()
                .eq(UserPlan::getUserId, userId)
                .eq(UserPlan::getPlanId, planId)
                .last("limit 1"));

        PlanDetailVO vo = new PlanDetailVO();
        vo.setId(plan.getId());
        vo.setTitle(plan.getTitle());
        vo.setSummary(plan.getSummary());
        vo.setCoverUrl(plan.getCoverUrl());
        vo.setGoal(plan.getGoal());
        vo.setDifficulty(plan.getDifficulty());
        vo.setDifficultyLabel(PlanConstants.difficultyLabel(plan.getDifficulty()));
        vo.setTrainDaysPerWeek(plan.getTrainDaysPerWeek());
        vo.setKcalEstimate(plan.getKcalEstimate());
        vo.setEquipment(PlanJsonUtils.toList(plan.getEquipmentJson()));
        vo.setSource(plan.getSource());
        vo.setOfficial(Objects.equals(plan.getSource(), PlanConstants.SOURCE_OFFICIAL));
        vo.setEditable(isOwner(plan, userId));
        vo.setUserPlanStatus(userPlan == null ? null : userPlan.getStatus());
        vo.setUserPlanId(userPlan == null ? null : userPlan.getId());
        vo.setDays(dayVos);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createPlan(PlanCreateDTO dto) {
        Long userId = LoginUser.id();

        String goal = hasText(dto.getGoal()) ? dto.getGoal() : PlanConstants.GOALS.get(0);
        if (!PlanConstants.GOALS.contains(goal)) {
            throw new BizIllegalException(400, "训练目标只有「增肌」和「减脂」两种");
        }

        Plan plan = new Plan()
                // ★ 默认名由后端给：前端拼一份、后端拼一份，两边一定会有对不上的那天
                .setTitle(hasText(dto.getTitle()) ? dto.getTitle().trim() : PlanConstants.DEFAULT_PLAN_TITLE)
                .setSummary(dto.getSummary())
                .setGoal(goal)
                .setDifficulty(dto.getDifficulty() == null ? 0 : dto.getDifficulty())
                .setTrainDaysPerWeek(0)
                .setKcalEstimate(0)
                .setSource(PlanConstants.SOURCE_USER)
                .setStatus(PlanConstants.PLAN_PUBLISHED)
                .setRefCount(0)
                .setOwnerId(userId);
        save(plan);
        log.info("创建计划：userId={}, planId={}, title={}", userId, plan.getId(), plan.getTitle());
        return plan.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePlan(Long planId, PlanUpdateDTO dto) {
        Long userId = LoginUser.id();
        Plan plan = getById(planId);
        if (plan == null) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.PLAN_NOT_EXISTS);
        }
        // 官方计划一律不许改：它是所有用户共享的模板，改了等于改别人的计划。
        // 想自定义请「执行本计划」后另存一份（这个动作 P1 再补，现在提示语已经这么写了）。
        if (!isOwner(plan, userId)) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.PLAN_NOT_OWNER);
        }

        Plan patch = new Plan().setId(planId);
        // 语义与"训练日重命名"一致：不传 / 空串 = 不改（前端编辑面板常常只改其中一项）
        if (hasText(dto.getTitle())) {
            patch.setTitle(dto.getTitle().trim());
        }
        if (dto.getSummary() != null) {
            // 描述是唯一允许"传空串 = 清空"的字段
            patch.setSummary(dto.getSummary().trim());
        }
        if (hasText(dto.getGoal())) {
            String goal = dto.getGoal().trim();
            if (!PlanConstants.GOALS.contains(goal)) {
                throw new BizIllegalException(400, "训练目标只有「增肌」和「减脂」两种");
            }
            patch.setGoal(goal);
        }
        if (dto.getDifficulty() != null) {
            patch.setDifficulty(dto.getDifficulty());
        }
        // ★ MP 默认 NOT_NULL 更新策略：没 set 的字段不会进 SQL，
        //   所以不会把 train_days_per_week / kcal_estimate 冲成 null
        updateById(patch);
        log.info("编辑计划：userId={}, planId={}, title={}", userId, planId, patch.getTitle());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long executePlan(Long planId) {
        Long userId = LoginUser.id();

        Plan plan = getById(planId);
        if (plan == null || !visibleTo(plan, userId)) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.PLAN_NOT_EXISTS);
        }
        if (!Objects.equals(plan.getStatus(), PlanConstants.PLAN_PUBLISHED)) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.PLAN_NOT_ONLINE);
        }

        List<PlanDay> days = planDayMapper.selectList(Wrappers.<PlanDay>lambdaQuery()
                .eq(PlanDay::getPlanId, planId)
                .orderByAsc(PlanDay::getDayIndex));
        if (days.isEmpty()) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.PLAN_NO_DAY);
        }

        // ★ 空训练日必须拦下来，并且**报出是哪一天**：
        //   "参数错误"没法指导用户，而"「周一练胸」还没有动作，先给它加一个动作再执行"能。
        List<Long> dayIds = days.stream().map(PlanDay::getId).toList();
        Map<Long, Long> itemCountMap = planDayItemMapper.selectList(Wrappers.<PlanDayItem>lambdaQuery()
                        .in(PlanDayItem::getPlanDayId, dayIds)
                        .select(PlanDayItem::getPlanDayId))
                .stream()
                .collect(Collectors.groupingBy(PlanDayItem::getPlanDayId, Collectors.counting()));
        for (PlanDay day : days) {
            if (itemCountMap.getOrDefault(day.getId(), 0L) == 0) {
                throw new BizIllegalException(400, String.format(PlanErrorInfo.Msg.DAY_NO_ITEM, day.getName()));
            }
        }

        // ★ 幂等写入：连点三次也只会有 1 行（唯一索引 + ON DUPLICATE KEY UPDATE）
        userPlanMapper.executePlan(userId, planId, LocalDate.now());
        baseMapper.increaseRefCount(planId);

        UserPlan userPlan = userPlanMapper.selectOne(Wrappers.<UserPlan>lambdaQuery()
                .eq(UserPlan::getUserId, userId)
                .eq(UserPlan::getPlanId, planId)
                .last("limit 1"));
        log.info("执行计划：userId={}, planId={}, userPlanId={}", userId, planId, userPlan == null ? null : userPlan.getId());
        return userPlan == null ? null : userPlan.getId();
    }

    @Override
    public TodayWorkoutVO queryToday() {
        Long userId = LoginUser.id();
        LocalDate today = LocalDate.now();

        TodayWorkoutVO vo = new TodayWorkoutVO();
        // 两个分钟数和"今天有没有练"必须同一次请求返回，否则会出现"卡片说练了、数字是昨天的"
        vo.setTodayMinutes(toMinutes(sessionMapper.sumDurationOfDate(userId, today)));
        vo.setTotalMinutes(toMinutes(sessionMapper.sumDurationAll(userId)));
        vo.setSessionFinished(false);

        UserPlan userPlan = userPlanMapper.selectOne(Wrappers.<UserPlan>lambdaQuery()
                .eq(UserPlan::getUserId, userId)
                .eq(UserPlan::getStatus, PlanConstants.USER_PLAN_RUNNING)
                .orderByDesc(UserPlan::getStartDate)
                .orderByDesc(UserPlan::getId)
                .last("limit 1"));

        if (userPlan == null) {
            // 态一：没有正在执行的计划
            vo.setHasPlan(false).setStatus(PlanConstants.TODAY_NO_PLAN)
                    .setStatusLabel("还没有计划")
                    .setTips("挑一个推荐计划，或者自己定制一个");
            return vo;
        }

        Plan plan = getById(userPlan.getPlanId());
        vo.setPlanId(userPlan.getPlanId());
        vo.setPlanTitle(plan == null ? null : plan.getTitle());
        vo.setHasPlan(true);

        // 今天星期几（1=周一 … 7=周日，正好等于 plan_day.weekday）
        int isoWeekday = today.getDayOfWeek().getValue();
        PlanDay day = planDayMapper.selectOne(Wrappers.<PlanDay>lambdaQuery()
                .eq(PlanDay::getPlanId, userPlan.getPlanId())
                .eq(PlanDay::getWeekday, isoWeekday)
                .orderByAsc(PlanDay::getDayIndex)
                .last("limit 1"));

        if (day == null) {
            // 态二：有计划，今天休息
            vo.setStatus(PlanConstants.TODAY_REST)
                    .setStatusLabel("今天休息")
                    .setTips("今天没有安排训练，休息也是训练的一部分");
            return vo;
        }

        // 态三：今天要练
        Long itemCount = planDayItemMapper.selectCount(Wrappers.<PlanDayItem>lambdaQuery()
                .eq(PlanDayItem::getPlanDayId, day.getId()));
        vo.setStatus(PlanConstants.TODAY_TRAIN)
                .setStatusLabel("今天要练")
                .setTips("按计划练完这一组，今天的任务就完成了")
                .setPlanDayId(day.getId())
                .setDayName(day.getName())
                .setDayIndex(day.getDayIndex())
                .setExerciseCount(itemCount.intValue())
                .setEstMinutes(day.getEstMinutes());

        WorkoutSession session = sessionMapper.selectOne(Wrappers.<WorkoutSession>lambdaQuery()
                .eq(WorkoutSession::getUserId, userId)
                .eq(WorkoutSession::getSessionDate, today)
                .eq(WorkoutSession::getPlanDayId, day.getId())
                .last("limit 1"));
        if (session != null) {
            vo.setSessionId(session.getId());
            vo.setSessionFinished(Objects.equals(session.getStatus(), PlanConstants.SESSION_FINISHED));
        }
        return vo;
    }

    // ==================== 内部 ====================

    /** 一次查完训练日 + 动作项 + 动作，装配成 VO（3 次查询，不随训练日数量增长） */
    private List<PlanDayVO> assembleDays(List<PlanDay> days) {
        if (days.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> dayIds = days.stream().map(PlanDay::getId).toList();
        List<PlanDayItem> items = planDayItemMapper.selectList(Wrappers.<PlanDayItem>lambdaQuery()
                .in(PlanDayItem::getPlanDayId, dayIds)
                .orderByAsc(PlanDayItem::getPlanDayId)
                .orderByAsc(PlanDayItem::getSort));

        List<Long> exerciseIds = items.stream().map(PlanDayItem::getExerciseId).distinct().toList();
        Map<Long, Exercise> exerciseMap = exerciseIds.isEmpty()
                ? Collections.emptyMap()
                : exerciseMapper.selectBatchIds(exerciseIds).stream()
                        .collect(Collectors.toMap(Exercise::getId, Function.identity(), (a, b) -> a));

        Map<Long, List<PlanDayItem>> itemMap = items.stream()
                .collect(Collectors.groupingBy(PlanDayItem::getPlanDayId));

        List<PlanDayVO> result = new ArrayList<>(days.size());
        for (PlanDay day : days) {
            result.add(PlanVoAssembler.toDayVo(day, itemMap.getOrDefault(day.getId(), List.of()), exerciseMap));
        }
        return result;
    }

    /** 官方计划所有人可见；自建 / AI 计划只有 owner 可见 */
    private boolean visibleTo(Plan plan, Long userId) {
        if (Objects.equals(plan.getSource(), PlanConstants.SOURCE_OFFICIAL)) {
            return true;
        }
        return isOwner(plan, userId);
    }

    private boolean isOwner(Plan plan, Long userId) {
        return plan.getOwnerId() != null && plan.getOwnerId() > 0 && Objects.equals(plan.getOwnerId(), userId);
    }

    /** 秒 → 分钟（四舍五入；<30 秒的训练显示 0 分钟比显示 1 分钟更诚实） */
    private int toMinutes(long seconds) {
        return (int) Math.round(seconds / 60.0);
    }

    private String userPlanStatusLabel(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case PlanConstants.USER_PLAN_RUNNING -> "进行中";
            case PlanConstants.USER_PLAN_FINISHED -> "已完成";
            case PlanConstants.USER_PLAN_GIVE_UP -> "已放弃";
            default -> "";
        };
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
