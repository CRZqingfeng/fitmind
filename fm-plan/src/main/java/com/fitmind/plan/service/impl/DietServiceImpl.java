package com.fitmind.plan.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.plan.constants.DietConstants;
import com.fitmind.plan.constants.DietErrorInfo;
import com.fitmind.plan.domain.dto.DietLogCreateDTO;
import com.fitmind.plan.domain.po.DietLog;
import com.fitmind.plan.domain.po.DietPlan;
import com.fitmind.plan.domain.po.DietPlanDay;
import com.fitmind.plan.domain.po.DietPlanMeal;
import com.fitmind.plan.domain.po.DietPlanMealItem;
import com.fitmind.plan.domain.po.Food;
import com.fitmind.plan.domain.po.UserBodyMetric;
import com.fitmind.plan.domain.vo.DietLogCreatedVO;
import com.fitmind.plan.domain.vo.DietPlanCardVO;
import com.fitmind.plan.domain.vo.DietPlanDetailVO;
import com.fitmind.plan.domain.vo.DietTodayVO;
import com.fitmind.plan.domain.vo.MacroSumVO;
import com.fitmind.plan.domain.vo.MacroVO;
import com.fitmind.plan.mapper.DietLogMapper;
import com.fitmind.plan.mapper.DietPlanDayMapper;
import com.fitmind.plan.mapper.DietPlanMapper;
import com.fitmind.plan.mapper.DietPlanMealItemMapper;
import com.fitmind.plan.mapper.DietPlanMealMapper;
import com.fitmind.plan.mapper.FoodMapper;
import com.fitmind.plan.mapper.SportLogMapper;
import com.fitmind.plan.service.IBodyService;
import com.fitmind.plan.service.IDietService;
import com.fitmind.plan.utils.FoodUnits;
import com.fitmind.plan.utils.LoginUser;
import com.fitmind.plan.utils.NutritionCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 饮食实现。
 *
 * <p><b>本类最容易写错的地方</b>：</p>
 * <ol>
 *   <li>{@code remainKcal} 用 {@code Math.max(0, ...)} 兜底 —— 吃超了会显示 0，
 *       用户以为"刚好达标"，实际超了 300 千卡。这里**允许负数**。</li>
 *   <li>把运动消耗算进 {@code remainKcal} —— 口径 1 明确不抵扣。
 *       抵扣的后果是"越练越能吃"，把用户往补偿性进食上引。</li>
 *   <li>记餐时由前端传 kcal —— 热量必须由后端按 {@code food 单价 × 折算克数} 算，
 *       前端算的那份会和历史记录的算法分叉（改一次单价就对不上）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DietServiceImpl implements IDietService {

    private final DietLogMapper dietLogMapper;
    private final FoodMapper foodMapper;
    private final DietPlanMapper dietPlanMapper;
    private final DietPlanDayMapper dietPlanDayMapper;
    private final DietPlanMealMapper dietPlanMealMapper;
    private final DietPlanMealItemMapper dietPlanMealItemMapper;
    private final SportLogMapper sportLogMapper;
    private final IBodyService bodyService;

    @Override
    public DietTodayVO queryToday(LocalDate date) {
        Long userId = LoginUser.id();
        LocalDate day = date == null ? LocalDate.now() : date;

        // 体征：库里有就用库里的；没有就用 fm-user 档案兜底并落一行；再没有 = 没预算
        UserBodyMetric metric = bodyService.ensureMetric(userId);

        MacroSumVO sum = dietLogMapper.sumOfDate(userId, day);
        int intake = sum == null || sum.getKcal() == null ? 0 : sum.getKcal();
        BigDecimal protein = sum == null ? BigDecimal.ZERO : nz(sum.getProtein());
        BigDecimal carb = sum == null ? BigDecimal.ZERO : nz(sum.getCarb());
        BigDecimal fat = sum == null ? BigDecimal.ZERO : nz(sum.getFat());

        int budget = metric == null ? 0 : nz(metric.getKcalBudget());

        DietTodayVO vo = new DietTodayVO();
        vo.setDate(day);
        vo.setIntakeKcal(intake);
        vo.setBudgetKcal(budget);
        // ★ 没有预算时返回 null（前端显示"先记录体重"），不要返回 -intake 那种莫名其妙的数
        vo.setRemainKcal(metric == null ? null : budget - intake);
        vo.setWeightKg(metric == null ? null : metric.getWeightKg());
        vo.setBmi(metric == null ? null : metric.getBmi());
        vo.setTargetWeightKg(metric == null ? null : metric.getTargetWeightKg());
        vo.setNeedProfile(metric == null);

        // 三大营养素：current 来自摄入，target 来自体征（没体征时 target 给 0，配合 needProfile 引导）
        vo.setMacros(List.of(
                new MacroVO("carb", "碳水", carb,
                        metric == null ? BigDecimal.ZERO : nz(metric.getCarbG()), "g"),
                new MacroVO("protein", "蛋白", protein,
                        metric == null ? BigDecimal.ZERO : nz(metric.getProteinG()), "g"),
                new MacroVO("fat", "脂肪", fat,
                        metric == null ? BigDecimal.ZERO : nz(metric.getFatG()), "g")));

        // 运动消耗：只展示不抵扣（口径 1），但必须有这一行，
        // 否则用户记完运动看不到任何变化，会以为功能坏了
        int sportKcal = (int) sportLogMapper.sumKcalOfDate(userId, day);
        vo.setSportKcal(sportKcal);
        vo.setSportNote(sportKcal > 0
                ? "运动消耗 " + sportKcal + " 千卡 · 不计入预算"
                : "运动消耗会单独记，不影响「还可以吃」");

        vo.setMeals(buildMeals(userId, day));
        return vo;
    }

    @Override
    public List<DietPlanCardVO> queryRecommend() {
        LoginUser.id();
        return dietPlanMapper.selectList(Wrappers.<DietPlan>lambdaQuery()
                        .eq(DietPlan::getSource, 0)
                        .eq(DietPlan::getStatus, 1)
                        .orderByDesc(DietPlan::getRefCount)
                        .orderByAsc(DietPlan::getId)
                        .last("limit 20"))
                .stream().map(this::toCardVo).toList();
    }

    @Override
    public DietPlanDetailVO queryPlanDetail(Long planId, Integer day) {
        LoginUser.id();

        DietPlan plan = dietPlanMapper.selectById(planId);
        if (plan == null || !Integer.valueOf(1).equals(plan.getStatus())) {
            throw new BizIllegalException(400, DietErrorInfo.Msg.DIET_PLAN_NOT_EXISTS);
        }

        List<DietPlanDay> days = dietPlanDayMapper.selectList(Wrappers.<DietPlanDay>lambdaQuery()
                .eq(DietPlanDay::getDietPlanId, planId)
                .orderByAsc(DietPlanDay::getDayIndex));
        if (days.isEmpty()) {
            DietPlanDetailVO empty = baseDetail(plan);
            empty.setDays(List.of());
            return empty;
        }
        if (day != null && days.stream().noneMatch(d -> Objects.equals(d.getDayIndex(), day))) {
            throw new BizIllegalException(400, String.format(DietErrorInfo.Msg.DIET_PLAN_DAY_ILLEGAL, day));
        }

        List<Long> dayIds = days.stream().map(DietPlanDay::getId).toList();
        List<DietPlanMeal> meals = dietPlanMealMapper.selectList(Wrappers.<DietPlanMeal>lambdaQuery()
                .in(DietPlanMeal::getDietPlanDayId, dayIds)
                .orderByAsc(DietPlanMeal::getMealTime));
        List<Long> mealIds = meals.stream().map(DietPlanMeal::getId).toList();
        List<DietPlanMealItem> items = mealIds.isEmpty() ? List.of()
                : dietPlanMealItemMapper.selectList(Wrappers.<DietPlanMealItem>lambdaQuery()
                        .in(DietPlanMealItem::getMealId, mealIds)
                        .orderByAsc(DietPlanMealItem::getSort));

        List<Long> foodIds = items.stream().map(DietPlanMealItem::getFoodId).distinct().toList();
        Map<Long, Food> foodMap = foodIds.isEmpty() ? Collections.emptyMap()
                : foodMapper.selectBatchIds(foodIds).stream()
                .collect(Collectors.toMap(Food::getId, Function.identity(), (a, b) -> a));

        Map<Long, List<DietPlanMealItem>> itemsByMeal = items.stream()
                .collect(Collectors.groupingBy(DietPlanMealItem::getMealId));
        Map<Long, List<DietPlanMeal>> mealsByDay = meals.stream()
                .collect(Collectors.groupingBy(DietPlanMeal::getDietPlanDayId));

        List<DietPlanDetailVO.DayVO> dayVos = new ArrayList<>(days.size());
        for (DietPlanDay d : days) {
            List<DietPlanDetailVO.MealVO> mealVos = new ArrayList<>();
            BigDecimal dayP = BigDecimal.ZERO;
            BigDecimal dayC = BigDecimal.ZERO;
            BigDecimal dayF = BigDecimal.ZERO;
            int dayKcal = 0;

            for (DietPlanMeal m : mealsByDay.getOrDefault(d.getId(), List.of())) {
                List<DietPlanDetailVO.ItemVO> itemVos = new ArrayList<>();
                int mealKcal = 0;
                for (DietPlanMealItem it : itemsByMeal.getOrDefault(m.getId(), List.of())) {
                    Food food = foodMap.get(it.getFoodId());
                    BigDecimal gram = gramOf(it.getAmount(), it.getUnit(), food);
                    // ★ 展示口径：按 food **现价**算，而不是 item.kcal 那个导入快照
                    int kcal = NutritionCalculator.kcalOf(food == null ? null : food.getKcalPer100g(), gram);
                    mealKcal += kcal;
                    if (food != null) {
                        dayP = dayP.add(NutritionCalculator.nutrientOf(food.getProteinPer100g(), gram));
                        dayC = dayC.add(NutritionCalculator.nutrientOf(food.getCarbPer100g(), gram));
                        dayF = dayF.add(NutritionCalculator.nutrientOf(food.getFatPer100g(), gram));
                    }
                    itemVos.add(toItemVo(it, food, gram, kcal));
                }
                mealVos.add(toMealVo(m, mealKcal, itemVos));
                dayKcal += mealKcal;
            }

            DietPlanDetailVO.DayVO dayVo = new DietPlanDetailVO.DayVO();
            dayVo.setDayIndex(d.getDayIndex());
            dayVo.setKcalTotal(dayKcal);
            dayVo.setProteinG(scale1(dayP));
            dayVo.setCarbG(scale1(dayC));
            dayVo.setFatG(scale1(dayF));
            dayVo.setMeals(mealVos);
            dayVos.add(dayVo);
        }

        DietPlanDetailVO vo = baseDetail(plan);
        vo.setDays(dayVos);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DietLogCreatedVO createLog(DietLogCreateDTO dto) {
        Long userId = LoginUser.id();
        LocalDate date = dto.getDate() == null ? LocalDate.now() : dto.getDate();

        if (dto.getMealType() < 1 || dto.getMealType() > DietConstants.MEAL_LABELS.size()) {
            throw new BizIllegalException(400, DietErrorInfo.Msg.MEAL_TYPE_ILLEGAL);
        }
        if (dto.getAmount() == null || dto.getAmount().signum() <= 0) {
            throw new BizIllegalException(400, DietErrorInfo.Msg.AMOUNT_ILLEGAL);
        }

        Food food = foodMapper.selectById(dto.getFoodId());
        if (food == null || !Integer.valueOf(1).equals(food.getStatus())) {
            throw new BizIllegalException(400, DietErrorInfo.Msg.FOOD_NOT_EXISTS);
        }
        if (food.getOwnerId() != null && food.getOwnerId() > 0 && !food.getOwnerId().equals(userId)) {
            throw new BizIllegalException(400, DietErrorInfo.Msg.FOOD_NOT_EXISTS);
        }

        String unit = dto.getUnit().trim();
        BigDecimal perUnit = FoodUnits.toMap(food.getUnitsJson()).get(unit);
        if (perUnit == null) {
            // 单位必须是这个食物 units_json 里列的 —— 不做"任意单位自由换算"
            throw new BizIllegalException(400, DietErrorInfo.Msg.UNIT_ILLEGAL);
        }

        BigDecimal gram = dto.getAmount().multiply(perUnit);
        int kcal = NutritionCalculator.kcalOf(food.getKcalPer100g(), gram);

        DietLog record = new DietLog()
                .setUserId(userId)
                .setLogDate(date)
                .setMealType(dto.getMealType())
                .setFoodId(food.getId())
                // ★ 快照三件套：名字 / 热量 / 三大营养素（食物库改价，历史不变）
                .setFoodName(food.getName())
                .setAmount(dto.getAmount())
                .setUnit(unit)
                .setGram(gram)
                .setKcal(kcal)
                .setProteinG(NutritionCalculator.nutrientOf(food.getProteinPer100g(), gram))
                .setCarbG(NutritionCalculator.nutrientOf(food.getCarbPer100g(), gram))
                .setFatG(NutritionCalculator.nutrientOf(food.getFatPer100g(), gram));

        // 唯一索引 + 六列一起累加：合并靠索引，不靠"先查有没有"
        dietLogMapper.upsertMerge(record);

        DietLog saved = dietLogMapper.selectOne(Wrappers.<DietLog>lambdaQuery()
                .eq(DietLog::getUserId, userId)
                .eq(DietLog::getLogDate, date)
                .eq(DietLog::getMealType, dto.getMealType())
                .eq(DietLog::getFoodId, food.getId())
                .eq(DietLog::getUnit, unit)
                .last("limit 1"));

        BigDecimal totalAmount = saved == null ? dto.getAmount() : saved.getAmount();
        boolean merged = totalAmount.compareTo(dto.getAmount()) > 0;

        DietLogCreatedVO vo = new DietLogCreatedVO();
        vo.setLogId(saved == null ? null : saved.getId());
        vo.setKcal(kcal);
        vo.setAmount(dto.getAmount());
        vo.setUnit(unit);
        vo.setGram(gram);
        vo.setMerged(merged);
        vo.setTotalAmount(totalAmount);
        vo.setMessage(merged
                ? "已累加为 " + stripZeros(totalAmount) + unit
                : "已记 " + stripZeros(dto.getAmount()) + unit);
        log.info("记饮食：userId={}, date={}, meal={}, food={}, {} {}, {}千卡, merged={}",
                userId, date, dto.getMealType(), food.getName(), dto.getAmount(), unit, kcal, merged);
        return vo;
    }

    @Override
    public void removeLog(Long id) {
        Long userId = LoginUser.id();
        DietLog record = dietLogMapper.selectById(id);
        if (record == null) {
            throw new BizIllegalException(400, DietErrorInfo.Msg.LOG_NOT_EXISTS);
        }
        if (!Objects.equals(record.getUserId(), userId)) {
            throw new BizIllegalException(403, DietErrorInfo.Msg.LOG_NOT_OWNER);
        }
        dietLogMapper.deleteById(id);
    }

    // ==================== 内部 ====================

    /** 四餐明细：固定返回 4 条（没记的也给空壳），前端才能稳定渲染 4 张卡 */
    private List<DietTodayVO.MealVO> buildMeals(Long userId, LocalDate date) {
        List<DietLog> logs = dietLogMapper.selectList(Wrappers.<DietLog>lambdaQuery()
                .eq(DietLog::getUserId, userId)
                .eq(DietLog::getLogDate, date)
                .orderByAsc(DietLog::getMealType)
                .orderByAsc(DietLog::getId));

        Map<Long, Food> foodMap = logs.isEmpty() ? Collections.emptyMap()
                : foodMapper.selectBatchIds(logs.stream().map(DietLog::getFoodId).distinct().toList())
                .stream().collect(Collectors.toMap(Food::getId, Function.identity(), (a, b) -> a));

        Map<Integer, List<DietLog>> byMeal = logs.stream().collect(Collectors.groupingBy(DietLog::getMealType));

        List<DietTodayVO.MealVO> meals = new ArrayList<>(DietConstants.MEAL_LABELS.size());
        for (int type = 1; type <= DietConstants.MEAL_LABELS.size(); type++) {
            List<DietLog> list = byMeal.getOrDefault(type, List.of());
            DietTodayVO.MealVO meal = new DietTodayVO.MealVO();
            meal.setMealType(type);
            meal.setLabel(DietConstants.MEAL_LABELS.get(type - 1));
            meal.setKcal(list.stream().mapToInt(l -> nz(l.getKcal())).sum());
            meal.setItems(list.stream().map(l -> toItemVo(l, foodMap.get(l.getFoodId()))).toList());
            meals.add(meal);
        }
        return meals;
    }

    private DietTodayVO.ItemVO toItemVo(DietLog log, Food food) {
        DietTodayVO.ItemVO vo = new DietTodayVO.ItemVO();
        vo.setId(log.getId());
        vo.setFoodName(log.getFoodName());
        vo.setAmount(log.getAmount());
        vo.setUnit(log.getUnit());
        vo.setGram(log.getGram());
        vo.setKcal(log.getKcal());
        vo.setEmoji(food == null ? "🍽" : food.getEmoji());
        return vo;
    }

    private DietPlanCardVO toCardVo(DietPlan plan) {
        DietPlanCardVO vo = new DietPlanCardVO();
        vo.setId(plan.getId());
        vo.setTitle(plan.getTitle());
        vo.setSummary(plan.getSummary());
        vo.setCoverUrl(plan.getCoverUrl());
        vo.setGoal(plan.getGoal());
        vo.setCycleDays(plan.getCycleDays());
        vo.setKcalPerDay(plan.getKcalPerDay());
        vo.setSubtitle("一周期" + plan.getCycleDays() + "天 · " + plan.getGoal()
                + " · " + plan.getKcalPerDay() + "千卡");
        vo.setTags(List.of());
        return vo;
    }

    private DietPlanDetailVO baseDetail(DietPlan plan) {
        DietPlanDetailVO vo = new DietPlanDetailVO();
        vo.setId(plan.getId());
        vo.setTitle(plan.getTitle());
        vo.setSummary(plan.getSummary());
        vo.setCoverUrl(plan.getCoverUrl());
        vo.setGoal(plan.getGoal());
        vo.setCycleDays(plan.getCycleDays());
        vo.setKcalPerDay(plan.getKcalPerDay());
        vo.setCycleText("一周期" + plan.getCycleDays() + "天");
        return vo;
    }

    private DietPlanDetailVO.MealVO toMealVo(DietPlanMeal meal, int kcal, List<DietPlanDetailVO.ItemVO> items) {
        DietPlanDetailVO.MealVO vo = new DietPlanDetailVO.MealVO();
        vo.setMealType(meal.getMealType());
        vo.setMealIndex(meal.getMealIndex());
        vo.setLabel(mealLabel(meal.getMealType(), meal.getMealIndex()));
        vo.setMealTime(formatTime(meal.getMealTime()));
        vo.setKcal(kcal);
        vo.setItems(items);
        return vo;
    }

    /**
     * 餐次展示名。
     *
     * <p>★ 加餐要带序号：「加餐①」「加餐②」「加餐③」。
     * 只显示"加餐"三次的话，详情页会出现三个一模一样的标题，
     * 用户根本分不清 10:00 那次和 21:00 那次。</p>
     */
    private String mealLabel(Integer mealType, Integer mealIndex) {
        int type = mealType == null ? DietConstants.MEAL_BREAKFAST : mealType;
        String base = type >= 1 && type <= DietConstants.MEAL_LABELS.size()
                ? DietConstants.MEAL_LABELS.get(type - 1) : "餐";
        // ★ 加餐**一律带序号**：第一顿也要显示"加餐①"。
        //   第一版写的是 "mealIndex <= 1 就不带序号"，结果详情页出现
        //   "加餐 10:00 / 加餐② 15:30 / 加餐③ 21:00" —— 三顿加餐里唯一没编号的那一顿，
        //   反而最像是漏了编号，用户会以为它是"正餐"。
        if (type != DietConstants.MEAL_SNACK) {
            return base;
        }
        int index = mealIndex == null || mealIndex < 1 ? 1 : mealIndex;
        String[] circles = {"①", "②", "③", "④", "⑤"};
        int idx = Math.min(index - 1, circles.length - 1);
        return base + circles[idx];
    }

    /** LocalTime → "07:30"（★ 前端不要再切："07:30:00" 直接渲染会多两个零） */
    private String formatTime(LocalTime time) {
        if (time == null) {
            return null;
        }
        return String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    private DietPlanDetailVO.ItemVO toItemVo(DietPlanMealItem item, Food food, BigDecimal gram, int kcal) {
        DietPlanDetailVO.ItemVO vo = new DietPlanDetailVO.ItemVO();
        vo.setFoodId(item.getFoodId());
        vo.setFoodName(food == null ? "食物已下架" : food.getName());
        vo.setEmoji(food == null ? "🍽" : food.getEmoji());
        vo.setAmount(item.getAmount());
        vo.setUnit(item.getUnit());
        vo.setGram(gram);
        vo.setKcal(kcal);
        vo.setAmountText(amountText(item.getAmount(), item.getUnit(), gram));
        return vo;
    }

    /** "50g" / "1 碗(150g)" —— 单位不是克时把折算克数也带上，用户才知道自己吃了多少 */
    private String amountText(BigDecimal amount, String unit, BigDecimal gram) {
        String a = stripZeros(amount);
        if ("克".equals(unit)) {
            return a + "g";
        }
        return a + " " + unit + "(" + stripZeros(gram) + "g)";
    }

    private BigDecimal gramOf(BigDecimal amount, String unit, Food food) {
        if (food == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal per = FoodUnits.toMap(food.getUnitsJson()).get(unit);
        if (per == null) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(per);
    }

    private static String stripZeros(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal scale1(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
