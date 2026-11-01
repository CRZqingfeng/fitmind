package com.fitmind.plan.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitmind.api.client.user.UserClient;
import com.fitmind.api.dto.user.UserBodyProfileDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.plan.ai.AiPlanComposer;
import com.fitmind.plan.constants.DietConstants;
import com.fitmind.plan.constants.PlanConstants;
import com.fitmind.plan.constants.PlanErrorInfo;
import com.fitmind.plan.domain.dto.AiPreviewDTO;
import com.fitmind.plan.domain.po.AiPlanDraft;
import com.fitmind.plan.domain.po.DietPlan;
import com.fitmind.plan.domain.po.DietPlanDay;
import com.fitmind.plan.domain.po.DietPlanMeal;
import com.fitmind.plan.domain.po.DietPlanMealItem;
import com.fitmind.plan.domain.po.Plan;
import com.fitmind.plan.domain.po.PlanDay;
import com.fitmind.plan.domain.po.PlanDayItem;
import com.fitmind.plan.domain.po.UserPlan;
import com.fitmind.plan.domain.vo.AiApplyVO;
import com.fitmind.plan.domain.vo.AiPreviewVO;
import com.fitmind.plan.domain.vo.AiTargetVO;
import com.fitmind.plan.domain.vo.BodyMetricVO;
import com.fitmind.plan.mapper.AiPlanDraftMapper;
import com.fitmind.plan.mapper.DietPlanDayMapper;
import com.fitmind.plan.mapper.DietPlanMapper;
import com.fitmind.plan.mapper.DietPlanMealItemMapper;
import com.fitmind.plan.mapper.DietPlanMealMapper;
import com.fitmind.plan.mapper.PlanDayItemMapper;
import com.fitmind.plan.mapper.PlanDayMapper;
import com.fitmind.plan.mapper.PlanMapper;
import com.fitmind.plan.mapper.UserPlanMapper;
import com.fitmind.plan.service.IBodyService;
import com.fitmind.plan.service.IPlanAiService;
import com.fitmind.plan.service.IPlanService;
import com.fitmind.plan.utils.AiSkeletonNormalizer;
import com.fitmind.plan.utils.LoginUser;
import com.fitmind.plan.utils.NutritionCalculator;
import com.fitmind.plan.utils.PlanJsonUtils;
import com.fitmind.plan.utils.TargetForecast;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * AI 定制服务实现。
 *
 * <p><b>本类最容易写错的三处：</b></p>
 * <ol>
 *   <li><b>预览直接写成"已发布"</b>：那用户点一次「重新生成」，我的计划里就多一份计划；
 *       所以草稿必须 {@code status=0}，只有 apply 才置 1。</li>
 *   <li><b>每次 preview 都新建 plan 行</b>：点三次「重新生成」就三份计划。
 *       这里靠 {@code uk_draft_user} + "复用未接受的草稿"解决。</li>
 *   <li><b>apply 认前端传的 planId</b>：那就能接受别人的草稿。
 *       这里<b>只认 previewId</b>，并且必须属于当前用户。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanAiServiceImpl implements IPlanAiService {

    /** BMI 刻度条的区间：<15 全在左端，>35 全在右端 */
    private static final double BMI_SCALE_MIN = 15.0;
    private static final double BMI_SCALE_MAX = 35.0;

    private final IBodyService bodyService;
    private final IPlanService planService;
    private final AiPlanComposer composer;
    private final UserClient userClient;

    private final PlanMapper planMapper;
    private final PlanDayMapper planDayMapper;
    private final PlanDayItemMapper planDayItemMapper;
    private final DietPlanMapper dietPlanMapper;
    private final DietPlanDayMapper dietPlanDayMapper;
    private final DietPlanMealMapper dietPlanMealMapper;
    private final DietPlanMealItemMapper dietPlanMealItemMapper;
    private final AiPlanDraftMapper draftMapper;
    private final UserPlanMapper userPlanMapper;

    // ==================== 目标推算 ====================

    @Override
    public AiTargetVO queryTarget() {
        return toTargetVo(bodyService.latest());
    }

    private AiTargetVO toTargetVo(BodyMetricVO metric) {
        AiTargetVO vo = new AiTargetVO();
        boolean needProfile = metric == null || Boolean.TRUE.equals(metric.getNeedProfile())
                || metric.getWeightKg() == null;
        vo.setNeedProfile(needProfile);
        vo.setFromProfile(metric != null && Boolean.TRUE.equals(metric.getFromProfile()));

        if (needProfile) {
            // 没有体重就不推算：空对象 + needProfile=true（前端显示"先记录体重"，不画 0/0）
            vo.setForecast(List.of());
            return vo;
        }

        vo.setCurrentWeightKg(metric.getWeightKg());
        vo.setTargetWeightKg(metric.getTargetWeightKg());
        vo.setCurrentBmi(metric.getBmi());

        TargetForecast.Result r = TargetForecast.compute(
                metric.getWeightKg(), metric.getTargetWeightKg(), LocalDate.now());
        vo.setWeeklyDeltaKg(r.weeklyDeltaKg());
        vo.setDaysToGoal(r.daysToGoal());
        vo.setTargetDate(r.targetDate());
        vo.setForecast(r.forecast().stream()
                .map(p -> new AiTargetVO.ForecastPoint(p.date(), p.weightKg()))
                .toList());
        return vo;
    }

    // ==================== 预览 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiPreviewVO preview(AiPreviewDTO dto) {
        Long userId = LoginUser.id();

        BodyMetricVO metric = bodyService.latest();
        if (metric == null || metric.getWeightKg() == null || Boolean.TRUE.equals(metric.getNeedProfile())) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.AI_NEED_PROFILE);
        }

        UserBodyProfileDTO profile = safeProfile(userId);
        String goal = goalOf(metric.getWeightKg(), metric.getTargetWeightKg(), profile);
        Macro macro = macroOf(metric, goal);
        AiSkeletonNormalizer.Skeleton skeleton = AiSkeletonNormalizer.normalize(
                dto, profile == null ? null : profile.getWeeklyFrequency(), goal);

        List<String> notes = new ArrayList<>(skeleton.notes());

        // ---- 1. 拿草稿槽（复用 or 新建）----
        AiPlanDraft draft = draftMapper.selectOne(Wrappers.<AiPlanDraft>lambdaQuery()
                .eq(AiPlanDraft::getUserId, userId).last("limit 1"));
        Plan plan = draft == null ? null : planMapper.selectById(draft.getPlanId());
        DietPlan dietPlan = draft == null ? null : dietPlanMapper.selectById(draft.getDietPlanId());
        boolean reusable = plan != null && Objects.equals(plan.getStatus(), PlanConstants.PLAN_DRAFT);
        if (!reusable) {
            plan = createDraftPlan(userId, goal, skeleton, macro,
                    profile == null ? null : profile.getTrainingLevel());
            dietPlan = createDraftDiet(userId, goal, macro, dto);
        } else {
            clearTraining(plan.getId());
            clearDiet(dietPlan.getId());
            refreshPlanHeader(plan, goal, skeleton, macro, dto);
            refreshDietHeader(dietPlan, goal, macro, dto);
        }

        // ---- 2. 选材（动作 / 食物都由服务端从库里选）----
        AiPlanComposer.TrainingResult training = composer.composeTraining(
                plan.getId(), skeleton, profile == null ? null : profile.getTrainingLevel(),
                LocalDate.now(), notes);
        List<com.fitmind.plan.domain.vo.DietPlanDetailVO.DayVO> dietDays = composer.composeDiet(
                dietPlan.getId(), userId, skeleton, macro.budget(), notes);

        planMapper.updateById(new Plan().setId(plan.getId())
                .setTrainDaysPerWeek(training.days().size())
                .setEquipmentJson(PlanJsonUtils.toJson(training.equipments())));

        // ---- 3. 草稿指针（previewId 每次都换：重新生成必须真的重算）----
        String previewId = UUID.randomUUID().toString();
        if (draft == null) {
            draftMapper.insert(new AiPlanDraft()
                    .setUserId(userId)
                    .setPreviewId(previewId)
                    .setPlanId(plan.getId())
                    .setDietPlanId(dietPlan.getId())
                    .setSkeletonJson(toSkeletonJson(dto))
                    .setDegraded(Boolean.TRUE.equals(dto.getDegraded()) ? 1 : 0)
                    .setStatus(AiPlanDraft.STATUS_PENDING));
        } else {
            draftMapper.updateById(draft
                    .setPreviewId(previewId)
                    .setPlanId(plan.getId())
                    .setDietPlanId(dietPlan.getId())
                    .setSkeletonJson(toSkeletonJson(dto))
                    .setDegraded(Boolean.TRUE.equals(dto.getDegraded()) ? 1 : 0)
                    .setStatus(AiPlanDraft.STATUS_PENDING));
        }

        log.info("AI 预览生成：userId={}, previewId={}, planId={}, dietId={}, 训练日={}, 每日={}千卡, notes={}",
                userId, previewId, plan.getId(), dietPlan.getId(), training.days().size(), macro.budget(), notes);

        return assemblePreview(previewId, plan, dietPlan, metric, skeleton, macro, training, dietDays, dto, notes);
    }

    // ==================== 接受 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiApplyVO apply(String previewId) {
        Long userId = LoginUser.id();
        if (previewId == null || previewId.isBlank()) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.AI_PREVIEW_EXPIRED);
        }
        AiPlanDraft draft = draftMapper.selectOne(Wrappers.<AiPlanDraft>lambdaQuery()
                .eq(AiPlanDraft::getPreviewId, previewId).last("limit 1"));
        if (draft == null) {
            throw new BizIllegalException(400, PlanErrorInfo.Msg.AI_PREVIEW_EXPIRED);
        }
        // ★ 归属校验：不认前端传 planId，只认"这个 previewId 是不是你的"
        if (!Objects.equals(draft.getUserId(), userId)) {
            throw new BizIllegalException(403, PlanErrorInfo.Msg.AI_PREVIEW_NOT_MINE);
        }

        AiApplyVO vo = new AiApplyVO();
        vo.setPlanId(draft.getPlanId());
        vo.setDietPlanId(draft.getDietPlanId());
        if (Objects.equals(draft.getStatus(), AiPlanDraft.STATUS_ACCEPTED)) {
            // 幂等：第二次点「接受」不该再建一条 user_plan
            vo.setUserPlanId(userPlanIdOf(userId, draft.getPlanId()));
            return vo;
        }

        planMapper.updateById(new Plan().setId(draft.getPlanId()).setStatus(PlanConstants.PLAN_PUBLISHED));
        dietPlanMapper.updateById(new DietPlan().setId(draft.getDietPlanId()).setStatus(PlanConstants.PLAN_PUBLISHED));
        // 复用 P0 的 executePlan：幂等 + 空训练日拦截 + refCount 都在这儿，不再写一遍
        Long userPlanId = planService.executePlan(draft.getPlanId());
        draftMapper.updateById(draft.setStatus(AiPlanDraft.STATUS_ACCEPTED));
        vo.setUserPlanId(userPlanId);

        log.info("接受 AI 计划：userId={}, previewId={}, planId={}, userPlanId={}",
                userId, previewId, vo.getPlanId(), userPlanId);
        return vo;
    }

    /** 已接受时拿回原来的 user_plan id（幂等返回，不新建） */
    private Long userPlanIdOf(Long userId, Long planId) {
        UserPlan row = userPlanMapper.selectOne(Wrappers.<UserPlan>lambdaQuery()
                .eq(UserPlan::getUserId, userId)
                .eq(UserPlan::getPlanId, planId)
                .last("limit 1"));
        return row == null ? null : row.getId();
    }

    // ==================== 草稿的读写 ====================

    private Plan createDraftPlan(Long userId, String goal, AiSkeletonNormalizer.Skeleton skeleton,
                                 Macro macro, Integer trainingLevel) {
        Plan plan = new Plan()
                .setTitle("为你定制的" + goal + "训练计划")
                .setSummary(summaryOfTraining(skeleton, goal))
                .setGoal(goal)
                .setDifficulty(difficultyOf(trainingLevel))
                .setTrainDaysPerWeek(skeleton.weeklyFrequency())
                .setKcalEstimate(macro.budget())
                .setSource(PlanConstants.SOURCE_AI)
                .setStatus(PlanConstants.PLAN_DRAFT)
                .setRefCount(0)
                .setOwnerId(userId);
        planMapper.insert(plan);
        return plan;
    }

    private DietPlan createDraftDiet(Long userId, String goal, Macro macro, AiPreviewDTO dto) {
        DietPlan plan = new DietPlan()
                .setTitle("为你定制的" + goal + "饮食计划")
                .setSummary(hasText(dto.getDietAdvice()) ? dto.getDietAdvice()
                        : "按你的热量预算 " + macro.budget() + " 千卡排的 5 天饮食，可照着吃。")
                .setGoal(goal)
                .setCycleDays(AiPlanComposer.DIET_CYCLE_DAYS)
                .setKcalPerDay(macro.budget())
                .setSource(PlanConstants.SOURCE_AI)
                .setStatus(PlanConstants.PLAN_DRAFT)
                .setRefCount(0)
                .setOwnerId(userId);
        dietPlanMapper.insert(plan);
        return plan;
    }

    private void refreshPlanHeader(Plan plan, String goal, AiSkeletonNormalizer.Skeleton skeleton,
                                   Macro macro, AiPreviewDTO dto) {
        planMapper.updateById(new Plan().setId(plan.getId())
                .setTitle("为你定制的" + goal + "训练计划")
                .setSummary(summaryOfTraining(skeleton, goal))
                .setGoal(goal)
                .setTrainDaysPerWeek(skeleton.weeklyFrequency())
                .setKcalEstimate(macro.budget())
                // ★ 复用同一条草稿行时把状态写回 0：万一上一轮被手工改成 1，不能带着发布态继续预览
                .setStatus(PlanConstants.PLAN_DRAFT)
                .setSource(PlanConstants.SOURCE_AI));
    }

    private void refreshDietHeader(DietPlan plan, String goal, Macro macro, AiPreviewDTO dto) {
        dietPlanMapper.updateById(new DietPlan().setId(plan.getId())
                .setTitle("为你定制的" + goal + "饮食计划")
                .setSummary(hasText(dto.getDietAdvice()) ? dto.getDietAdvice()
                        : "按你的热量预算 " + macro.budget() + " 千卡排的 5 天饮食，可照着吃。")
                .setGoal(goal)
                .setCycleDays(AiPlanComposer.DIET_CYCLE_DAYS)
                .setKcalPerDay(macro.budget())
                .setStatus(PlanConstants.PLAN_DRAFT)
                .setSource(PlanConstants.SOURCE_AI));
    }

    /**
     * 清空上一轮草稿的训练日/动作项。
     *
     * <p>★ 先删子表再删父表（没有外键，删反了会留一堆孤儿 item）。
     * 为什么"清空重建"而不是"逐条 diff"：训练日/动作项的组合每次都变，
     * diff 的复杂度远高于重写，而量极小（5 天 × 6 个动作）。</p>
     */
    private void clearTraining(Long planId) {
        List<PlanDay> days = planDayMapper.selectList(Wrappers.<PlanDay>lambdaQuery()
                .eq(PlanDay::getPlanId, planId));
        if (days.isEmpty()) {
            return;
        }
        List<Long> dayIds = days.stream().map(PlanDay::getId).toList();
        planDayItemMapper.delete(Wrappers.<PlanDayItem>lambdaQuery().in(PlanDayItem::getPlanDayId, dayIds));
        planDayMapper.delete(Wrappers.<PlanDay>lambdaQuery().eq(PlanDay::getPlanId, planId));
    }

    private void clearDiet(Long dietPlanId) {
        List<DietPlanDay> days = dietPlanDayMapper.selectList(Wrappers.<DietPlanDay>lambdaQuery()
                .eq(DietPlanDay::getDietPlanId, dietPlanId));
        if (days.isEmpty()) {
            return;
        }
        List<Long> dayIds = days.stream().map(DietPlanDay::getId).toList();
        List<DietPlanMeal> meals = dietPlanMealMapper.selectList(Wrappers.<DietPlanMeal>lambdaQuery()
                .in(DietPlanMeal::getDietPlanDayId, dayIds));
        if (!meals.isEmpty()) {
            List<Long> mealIds = meals.stream().map(DietPlanMeal::getId).toList();
            dietPlanMealItemMapper.delete(Wrappers.<DietPlanMealItem>lambdaQuery()
                    .in(DietPlanMealItem::getMealId, mealIds));
            dietPlanMealMapper.delete(Wrappers.<DietPlanMeal>lambdaQuery()
                    .in(DietPlanMeal::getDietPlanDayId, dayIds));
        }
        dietPlanDayMapper.delete(Wrappers.<DietPlanDay>lambdaQuery().eq(DietPlanDay::getDietPlanId, dietPlanId));
    }

    // ==================== 组装 ====================

    private AiPreviewVO assemblePreview(String previewId, Plan plan, DietPlan dietPlan, BodyMetricVO metric,
                                        AiSkeletonNormalizer.Skeleton skeleton, Macro macro,
                                        AiPlanComposer.TrainingResult training,
                                        List<com.fitmind.plan.domain.vo.DietPlanDetailVO.DayVO> dietDays,
                                        AiPreviewDTO dto, List<String> notes) {
        AiPreviewVO vo = new AiPreviewVO();
        vo.setPreviewId(previewId);
        vo.setPlanDraftId(plan.getId());
        vo.setDietDraftId(dietPlan.getId());
        vo.setTarget(toTargetVo(metric));
        vo.setEvaluation(evaluationOf(metric));
        vo.setMacros(macroVo(macro));
        vo.setPlanTitle(plan.getTitle());
        vo.setDifficultyLabel(PlanConstants.difficultyLabel(plan.getDifficulty()));
        vo.setDietPlanTitle(dietPlan.getTitle());
        vo.setDietCycleDays(dietPlan.getCycleDays());
        vo.setDietDays(dietDays);
        vo.setTrainDays(training.days());
        vo.setDistribution(distributionOf(training.categoryCount()));
        vo.setAdvice(adviceOf(skeleton, macro, dto, notes));
        return vo;
    }

    private AiPreviewVO.EvaluationVO evaluationOf(BodyMetricVO metric) {
        AiPreviewVO.EvaluationVO ev = new AiPreviewVO.EvaluationVO();
        ev.setHeightCm(metric.getHeightCm());
        ev.setWeightKg(metric.getWeightKg());
        ev.setTargetWeightKg(metric.getTargetWeightKg());
        ev.setBmi(metric.getBmi());
        ev.setBmiLabel(bmiLabel(metric.getBmi()));
        ev.setBmiPercent(bmiPercent(metric.getBmi()));
        return ev;
    }

    private AiPreviewVO.MacrosVO macroVo(Macro macro) {
        AiPreviewVO.MacrosVO vo = new AiPreviewVO.MacrosVO();
        vo.setKcalBudget(macro.budget());
        vo.setItems(List.of(
                macroItem("protein", "蛋白", macro.proteinG(), 4, macro.budget()),
                macroItem("carb", "碳水", macro.carbG(), 4, macro.budget()),
                macroItem("fat", "脂肪", macro.fatG(), 9, macro.budget())));
        return vo;
    }

    private AiPreviewVO.MacroItemVO macroItem(String key, String label, BigDecimal gram,
                                             int kcalPerGram, int budget) {
        AiPreviewVO.MacroItemVO item = new AiPreviewVO.MacroItemVO();
        item.setKey(key);
        item.setLabel(label);
        item.setGram(gram);
        int kcal = gram == null ? 0 : gram.multiply(BigDecimal.valueOf(kcalPerGram))
                .setScale(0, RoundingMode.HALF_UP).intValue();
        item.setKcal(kcal);
        item.setPercent(budget <= 0 ? 0 : (int) Math.round(kcal * 100.0 / budget));
        return item;
    }

    /** 运动分布：从已选动作的 category 反推，<b>不编比例</b> */
    private AiPreviewVO.DistributionVO distributionOf(Map<String, Integer> categoryCount) {
        int total = categoryCount.values().stream().mapToInt(Integer::intValue).sum();
        AiPreviewVO.DistributionVO vo = new AiPreviewVO.DistributionVO();
        if (total == 0) {
            vo.setStrength(0.0);
            vo.setCardio(0.0);
            vo.setFlexibility(0.0);
            return vo;
        }
        double strength = round2(categoryCount.getOrDefault("力量", 0) * 1.0 / total);
        double cardio = round2(categoryCount.getOrDefault("有氧", 0) * 1.0 / total);
        // 柔韧用"1 − 其它"算：三个数各自四舍五入后加起来不等于 1，饼图会缺一小块
        vo.setStrength(strength);
        vo.setCardio(cardio);
        vo.setFlexibility(round2(1 - strength - cardio));
        return vo;
    }

    private AiPreviewVO.AdviceVO adviceOf(AiSkeletonNormalizer.Skeleton skeleton, Macro macro,
                                          AiPreviewDTO dto, List<String> notes) {
        AiPreviewVO.AdviceVO advice = new AiPreviewVO.AdviceVO();
        boolean degraded = Boolean.TRUE.equals(dto.getDegraded());
        advice.setDegraded(degraded);
        // ★ 降级时用模板句：用户拿到的仍是完整计划，只是"措辞"变成白开水
        advice.setDietAdvice(hasText(dto.getDietAdvice()) ? dto.getDietAdvice()
                : String.format("每天 %d 千卡，蛋白 %s g、碳水 %s g、脂肪 %s g，分 %d 餐吃完更稳。",
                macro.budget(), plain(macro.proteinG()), plain(macro.carbG()), plain(macro.fatG()),
                skeleton.mealSplit().size()));
        advice.setTrainingAdvice(hasText(dto.getTrainingAdvice()) ? dto.getTrainingAdvice()
                : String.format("一周 %d 练，按%s安排；强度 %s，先保证动作做标准再加重量。",
                skeleton.weeklyFrequency(), skeleton.division(), skeleton.intensity()));
        advice.setTargetTip(hasText(dto.getTargetTip()) ? dto.getTargetTip()
                : "按每周 " + plain(BigDecimal.valueOf(0.25)) + " kg 的节奏推进，比冲一阵停一阵更靠谱。");
        advice.setNotes(notes);
        return advice;
    }

    private String summaryOfTraining(AiSkeletonNormalizer.Skeleton skeleton, String goal) {
        return String.format("一周 %d 练 · %s · 强度 %s · %s",
                skeleton.weeklyFrequency(), skeleton.division(), skeleton.intensity(), goal);
    }

    // ==================== 小工具 ====================

    /** 预算与三大营养素：优先读体征行里已算好的值，缺了才调 NutritionCalculator 重算 */
    private record Macro(int budget, BigDecimal proteinG, BigDecimal carbG, BigDecimal fatG) {
    }

    private Macro macroOf(BodyMetricVO metric, String goal) {
        Integer budget = metric.getKcalBudget();
        if (budget != null && budget > 0
                && metric.getProteinG() != null && metric.getCarbG() != null && metric.getFatG() != null) {
            // ★ 读已落库的值，不重算：P1 的 /diet/today 读的也是这几个数，
            //   两边必须同源，否则"首页预算 1820 / 定制页预算 1825"这种矛盾迟早出现
            return new Macro(budget, metric.getProteinG(), metric.getCarbG(), metric.getFatG());
        }
        NutritionCalculator.MacroPlan plan = NutritionCalculator.plan(metric.getWeightKg(), goal);
        return new Macro(plan.kcalBudget(), plan.proteinG(), plan.carbG(), plan.fatG());
    }

    private String goalOf(BigDecimal weight, BigDecimal target, UserBodyProfileDTO profile) {
        if (target != null && weight != null) {
            int cmp = target.compareTo(weight);
            if (cmp < 0) {
                return PlanConstants.GOAL_LOSS;
            }
            if (cmp > 0) {
                return PlanConstants.GOAL_GAIN;
            }
        }
        // 目标体重没填或正好相等 → 看档案里填的目标
        if (profile != null && profile.getGoalType() != null && profile.getGoalType() == 2) {
            return PlanConstants.GOAL_LOSS;
        }
        return PlanConstants.GOAL_GAIN;
    }

    private int difficultyOf(Integer trainingLevel) {
        if (trainingLevel == null || trainingLevel <= 2) {
            return 0;
        }
        return trainingLevel == 3 ? 1 : 2;
    }

    private String bmiLabel(BigDecimal bmi) {
        if (bmi == null) {
            return null;
        }
        double v = bmi.doubleValue();
        if (v < 18.5) {
            return "偏瘦";
        }
        if (v < 24) {
            return "正常";
        }
        if (v < 28) {
            return "偏胖";
        }
        return "肥胖";
    }

    /** BMI 在刻度条上的位置（15~35 映射到 0~1），前端只负责画 */
    private Double bmiPercent(BigDecimal bmi) {
        if (bmi == null) {
            return null;
        }
        double p = (bmi.doubleValue() - BMI_SCALE_MIN) / (BMI_SCALE_MAX - BMI_SCALE_MIN);
        return round2(Math.max(0, Math.min(1, p)));
    }

    private UserBodyProfileDTO safeProfile(Long userId) {
        try {
            return userClient.queryBodyProfile(userId);
        } catch (Exception e) {
            // 档案是"锦上添花"：fm-user 没起时 P2 仍要能用（训练水平默认新手、周频默认 3）
            log.warn("查询身体档案失败（按无档案处理）：userId={}, 原因={}", userId, e.getMessage());
            return null;
        }
    }

    private String toSkeletonJson(AiPreviewDTO dto) {
        // 只留排查用的关键字段，不序列化整个 DTO（避免把不入库的东西也存下来）
        return String.format("{\"skeletonId\":%s,\"division\":%s,\"weeklyFrequency\":%s,\"intensity\":%s,"
                        + "\"mealSplitSize\":%d,\"degraded\":%s}",
                quote(dto.getSkeletonId()), quote(dto.getDivision()), dto.getWeeklyFrequency(),
                quote(dto.getIntensity()), dto.getMealSplit() == null ? 0 : dto.getMealSplit().size(),
                dto.getDegraded());
    }

    private String quote(String v) {
        return v == null ? "null" : "\"" + v.replace("\"", "'") + "\"";
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
