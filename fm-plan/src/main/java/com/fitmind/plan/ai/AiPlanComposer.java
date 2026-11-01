package com.fitmind.plan.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fitmind.plan.constants.DietConstants;
import com.fitmind.plan.constants.PlanConstants;
import com.fitmind.plan.domain.po.DietPlanDay;
import com.fitmind.plan.domain.po.DietPlanMeal;
import com.fitmind.plan.domain.po.DietPlanMealItem;
import com.fitmind.plan.domain.po.Exercise;
import com.fitmind.plan.domain.po.Food;
import com.fitmind.plan.domain.po.PlanDay;
import com.fitmind.plan.domain.po.PlanDayItem;
import com.fitmind.plan.domain.vo.DietPlanDetailVO;
import com.fitmind.plan.domain.vo.PlanDayVO;
import com.fitmind.plan.mapper.DietPlanDayMapper;
import com.fitmind.plan.mapper.DietPlanMealItemMapper;
import com.fitmind.plan.mapper.DietPlanMealMapper;
import com.fitmind.plan.mapper.ExerciseMapper;
import com.fitmind.plan.mapper.FoodMapper;
import com.fitmind.plan.mapper.PlanDayItemMapper;
import com.fitmind.plan.mapper.PlanDayMapper;
import com.fitmind.plan.utils.AiSkeletonNormalizer;
import com.fitmind.plan.utils.NutritionCalculator;
import com.fitmind.plan.utils.PlanTimeEstimator;
import com.fitmind.plan.utils.PlanVoAssembler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI 计划的**选材器** —— 把"骨架"变成库里的行。
 *
 * <p><b>本类最容易写错的三处：</b></p>
 * <ol>
 *   <li><b>让 AI 决定动作/食物</b>：模型会编 {@code exerciseId}，库里没有的 id 落进
 *       {@code plan_day_item}，详情页就是"动作已下架"。所以这里<b>只从库里选</b>，
 *       模型连 id 都没有机会给。</li>
 *   <li><b>同一天重复同一个动作</b>：做完 6 组深蹲再让你做 6 组深蹲，是"看起来排满了"的假计划。
 *       这里按部位 round-robin 取，动作池天然不重复。</li>
 *   <li><b>饮食凑热量时虚报</b>：为了凑到目标千卡硬塞一个不存在的食物，或者把偏差记成 0。
 *       这里允许 ±10% 偏差，超出就<b>如实写进 notes</b>，不粉饰。</li>
 * </ol>
 *
 * <p>★ 所有数值都来自已有的唯一实现：{@code PlanTimeEstimator}（时长）、
 * {@code NutritionCalculator}（热量折算）、{@code DietConstants.labelOf}（餐次名）。
 * 本类<b>不新增任何热量/时间公式</b>。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiPlanComposer {

    /** 饮食计划固定 5 天（对齐参考 App；与训练周频无关 —— 饮食周期和训练频率是两件事） */
    public static final int DIET_CYCLE_DAYS = 5;

    /** 每个训练日最多几个动作（再多就不是"一次训练"而是一份清单） */
    private static final int MAX_ITEMS_PER_DAY = 6;
    /** 少于这个数就值得在 notes 里说一句（用户会以为是 bug） */
    private static final int MIN_ITEMS_EXPECTED = 3;

    /** 部位组合：分化方式 → 每个训练日练哪些部位 */
    private static final Map<String, List<List<String>>> DIVISIONS = Map.of(
            AiSkeletonNormalizer.DIVISION_THREE, List.of(
                    List.of("胸", "肩", "三头"),
                    List.of("背", "二头", "斜方肌"),
                    List.of("腿", "臀", "小腿")),
            AiSkeletonNormalizer.DIVISION_UPPER_LOWER, List.of(
                    List.of("胸", "背", "肩", "二头", "三头"),
                    List.of("腿", "臀", "小腿", "腹部")),
            AiSkeletonNormalizer.DIVISION_FIVE, List.of(
                    List.of("胸"),
                    List.of("背"),
                    List.of("腿"),
                    List.of("肩"),
                    List.of("二头", "三头", "前臂")));

    /** 强度 → 组数/次数/组休（★ 三档就够，档位多了用户也分不清） */
    private static final Map<String, int[]> PRESCRIPTION = Map.of(
            AiSkeletonNormalizer.INTENSITY_LOW, new int[]{3, 10, 60},
            AiSkeletonNormalizer.INTENSITY_MEDIUM, new int[]{4, 8, 75},
            AiSkeletonNormalizer.INTENSITY_HIGH, new int[]{4, 6, 90});

    /** 水果关键词（数据集没有"水果"这个分类，只能按名字认；认不出就退回蔬菜，不编数据） */
    private static final List<String> FRUIT_WORDS = List.of(
            "苹果", "香蕉", "橙", "西瓜", "梨", "桃", "葡萄", "莓", "桔", "橘", "柚", "猕猴桃");

    private final PlanDayMapper planDayMapper;
    private final PlanDayItemMapper planDayItemMapper;
    private final ExerciseMapper exerciseMapper;
    private final DietPlanDayMapper dietPlanDayMapper;
    private final DietPlanMealMapper dietPlanMealMapper;
    private final DietPlanMealItemMapper dietPlanMealItemMapper;
    private final FoodMapper foodMapper;

    // ==================== 训练 ====================

    /**
     * 选材结果。
     *
     * @param days          训练日 VO（直接给前端画，形状与计划详情页一致）
     * @param categoryCount 动作分类计数（用来算"运动分布"，不编比例）
     * @param equipments    用到的器械（去重；计划详情页的器械横滑要它）
     */
    public record TrainingResult(List<PlanDayVO> days, Map<String, Integer> categoryCount,
                                 List<String> equipments) {
    }

    /**
     * 排训练。
     *
     * @param planId        草稿计划 id（已存在，本方法只往里写训练日/动作项）
     * @param skeleton      归一化后的骨架
     * @param trainingLevel 用户训练水平 1~4（null = 新手）
     * @param today         基准日：<b>第 1 个训练日 = 今天</b>
     * @param notes         补充说明（往这里写"动作不够"之类的实话）
     */
    public TrainingResult composeTraining(Long planId, AiSkeletonNormalizer.Skeleton skeleton,
                                          Integer trainingLevel, LocalDate today, List<String> notes) {
        int maxDifficulty = maxDifficultyOf(trainingLevel);
        ExercisePools pools = loadPools();
        Map<String, List<Exercise>> byPart = pools.byPart();
        List<List<String>> groups = DIVISIONS.getOrDefault(skeleton.division(),
                DIVISIONS.get(AiSkeletonNormalizer.DIVISION_THREE));

        int[] presc = PRESCRIPTION.getOrDefault(skeleton.intensity(),
                PRESCRIPTION.get(AiSkeletonNormalizer.INTENSITY_MEDIUM));
        // ★ 第 1 个训练日落在"今天"：否则用户接受完计划，首页显示"今天休息"，
        //   第一反应是"我明明刚生成了计划"。
        int todayWeekday = today.getDayOfWeek().getValue();

        List<PlanDayVO> dayVos = new ArrayList<>(skeleton.weeklyFrequency());
        Map<String, Integer> categoryCount = new LinkedHashMap<>();
        java.util.Set<String> equipments = new java.util.LinkedHashSet<>();

        for (int i = 1; i <= skeleton.weeklyFrequency(); i++) {
            List<String> parts = groups.get((i - 1) % groups.size());
            int weekday = ((todayWeekday - 1 + (i - 1)) % 7) + 1;

            List<Exercise> picked = new ArrayList<>(pickExercises(byPart, parts, maxDifficulty));
            if (picked.size() < MIN_ITEMS_EXPECTED) {
                notes.add(String.format("第 %d 练（%s）动作库只有 %d 个可用动作，已全部排入；导入全量动作后会更细",
                        i, String.join("+", parts), picked.size()));
            }

            PlanDay day = new PlanDay()
                    .setPlanId(planId)
                    .setDayIndex(i)
                    .setWeekday(weekday)
                    // 名称："周日 胸 + 三头" —— 进计划详情、今日训练卡都会显示它
                    .setName(PlanConstants.weekdayLabel(weekday) + " " + String.join(" + ", parts))
                    .setSort(i)
                    .setEstMinutes(0);
            planDayMapper.insert(day);

            List<PlanDayItem> items = new ArrayList<>(picked.size());
            for (int si = 0; si < picked.size(); si++) {
                Exercise e = picked.get(si);
                PlanDayItem item = new PlanDayItem()
                        .setPlanDayId(day.getId())
                        .setExerciseId(e.getId())
                        .setSort(si + 1)
                        .setGroupType(PlanConstants.GROUP_FORMAL)
                        .setSets(presc[0])
                        .setReps(presc[1])
                        // 建议重量不猜：数据集没有用户力量水平对应表，填 0 会让"共4组×8次×0kg"很怪
                        .setWeightKg(null)
                        .setRestSec(presc[2]);
                planDayItemMapper.insert(item);
                items.add(item);
                categoryCount.merge(e.getCategory(), 1, Integer::sum);
                if (e.getEquipment() != null) {
                    equipments.add(e.getEquipment());
                }
            }

            // ★ 每天收尾加一个拉伸（1 组 60 秒）。
            //   不加的话「运动分布」永远是 100% 力量 —— 那不是"数据诚实"，是计划不合理：
            //   真实计划练完都会拉伸，何况数据集里本来就有 5 个拉伸动作可用。
            Exercise stretch = pickStretch(pools.stretches(), i);
            if (stretch != null) {
                PlanDayItem stretchItem = new PlanDayItem()
                        .setPlanDayId(day.getId())
                        .setExerciseId(stretch.getId())
                        .setSort(items.size() + 1)
                        .setGroupType(PlanConstants.GROUP_STRETCH)
                        .setSets(1)
                        .setReps(0)
                        .setDurationSec(60)
                        .setRestSec(0);
                planDayItemMapper.insert(stretchItem);
                items.add(stretchItem);
                picked = new ArrayList<>(picked);
                picked.add(stretch);
                categoryCount.merge("拉伸", 1, Integer::sum);
            }

            int minutes = PlanTimeEstimator.estimateMinutes(items);
            planDayMapper.updateById(new PlanDay().setId(day.getId()).setEstMinutes(minutes));
            day.setEstMinutes(minutes);

            Map<Long, Exercise> exerciseMap = picked.stream()
                    .collect(Collectors.toMap(Exercise::getId, Function.identity(), (a, b) -> a));
            dayVos.add(PlanVoAssembler.toDayVo(day, items, exerciseMap));
        }

        log.info("AI 排训练：planId={}, 训练日={}, 动作项={}, 分布={}",
                planId, dayVos.size(), categoryCount.values().stream().mapToInt(Integer::intValue).sum(),
                categoryCount);
        return new TrainingResult(dayVos, categoryCount, List.copyOf(equipments));
    }

    /**
     * 按部位轮转取动作。
     *
     * <p>★ 为什么不是"随机抽 6 个"：随机意味着同一份档案点两次「重新生成」得到两套不同动作，
     * 用户会以为系统在乱来；而且验收没法断言。这里完全由 {@code id} 顺序决定，<b>可复现</b>。</p>
     */
    private List<Exercise> pickExercises(Map<String, List<Exercise>> byPart, List<String> parts,
                                         int maxDifficulty) {
        List<List<Exercise>> pools = new ArrayList<>(parts.size());
        for (String part : parts) {
            List<Exercise> all = byPart.getOrDefault(part, List.of());
            // 难度优先 ≤ 用户水平；一个都没有才放开（宁可给难的，也不要这一天没动作）
            List<Exercise> fit = all.stream()
                    .filter(e -> e.getDifficulty() == null || e.getDifficulty() <= maxDifficulty)
                    .toList();
            List<Exercise> pool = fit.isEmpty() ? all : fit;
            if (!pool.isEmpty()) {
                pools.add(pool);
            }
        }
        if (pools.isEmpty()) {
            return List.of();
        }

        int total = pools.stream().mapToInt(List::size).sum();
        int target = Math.min(MAX_ITEMS_PER_DAY, total);
        int[] cursor = new int[pools.size()];
        List<Exercise> picked = new ArrayList<>(target);
        while (picked.size() < target) {
            boolean moved = false;
            for (int k = 0; k < pools.size() && picked.size() < target; k++) {
                if (cursor[k] >= pools.get(k).size()) {
                    continue;
                }
                picked.add(pools.get(k).get(cursor[k]++));
                moved = true;
            }
            if (!moved) {
                break;
            }
        }
        return picked;
    }

    /**
     * 挑一个拉伸动作（按训练日轮换）。
     *
     * <p>为什么固定 1 组 60 秒：拉伸不需要"4 组 × 8 次"，那是力量动作的处方；
     * 而且 60 秒刚好让「预计分钟」多 1 分钟，与真实收尾时间接近。</p>
     */
    private Exercise pickStretch(List<Exercise> stretches, int dayIndex) {
        // 池子在 loadPools 里已经筛好"只要拉伸"，这里不再按 category 过滤
        // （★ 它就是"§7.1 过滤后拉伸消失"那个坑的补丁，别把这一步挪回调用方）
        return stretches.isEmpty() ? null : stretches.get(Math.floorMod(dayIndex - 1, stretches.size()));
    }

    /** 训练水平 1~4 → 允许的最高动作难度（1 新手 / 3 中级 / 5 高级） */
    private int maxDifficultyOf(Integer trainingLevel) {
        if (trainingLevel == null || trainingLevel <= 2) {
            return 1;
        }
        return trainingLevel == 3 ? 3 : 5;
    }

    /**
     * 两个池子：主项（力量）按部位分组 + 拉伸（收尾用）。
     *
     * <p><b>本方法最容易写错的地方：只查"力量"会连拉伸一起查没。</b>
     * 收尾拉伸是从同一批数据里捞 {@code category='拉伸'} 的；如果 SQL 只写
     * {@code .in(category, ["力量"])}，拉伸池就是空的 → 每个训练日少掉收尾 →
     * 页面上"柔韧"占比变 0，而验收里的"拉伸项数 = 训练日数"会红。</p>
     *
     * <p>★ 为什么必须在 SQL 层按分类过滤（而不是取全表再过滤）：
     * 全量 873 条里 123 条拉伸 / 14 条有氧 / 61 条弹跳。不过滤的话它们会混进
     * "6 个主项"里 —— 这类变化<b>不报错、不报红，只是结果变得不像话</b>。</p>
     */
    private ExercisePools loadPools() {
        // 一次查全表（873 条不到 1MB）再内存分组：比"每个部位一次查询"少 N 次往返
        List<Exercise> all = exerciseMapper.selectList(Wrappers.<Exercise>lambdaQuery()
                .eq(Exercise::getStatus, 1)
                .in(Exercise::getCategory, List.of(PlanConstants.CATEGORY_STRENGTH,
                        PlanConstants.CATEGORY_STRETCH))
                .orderByAsc(Exercise::getId));

        Map<String, List<Exercise>> byPart = all.stream()
                .filter(e -> PlanConstants.CATEGORY_STRENGTH.equals(e.getCategory()))
                .filter(e -> e.getBodyPart() != null)
                .collect(Collectors.groupingBy(Exercise::getBodyPart, LinkedHashMap::new, Collectors.toList()));

        List<Exercise> stretches = all.stream()
                .filter(e -> PlanConstants.CATEGORY_STRETCH.equals(e.getCategory()))
                .sorted(Comparator.comparing(Exercise::getId))
                .toList();

        return new ExercisePools(byPart, stretches);
    }

    /** 选材池：{@code byPart} 只含力量；{@code stretches} 只含拉伸（两者互补，见 loadPools） */
    private record ExercisePools(Map<String, List<Exercise>> byPart, List<Exercise> stretches) {
    }

    // ==================== 饮食 ====================

    /** 食物池：按用途分好组，免得每餐都去判断"这个食物算主食还是蛋白" */
    private record FoodPool(List<Food> staples, List<Food> proteins, List<Food> vegetables,
                            List<Food> dairy, List<Food> nuts, List<Food> fruits) {
    }

    /** 一份食物：食物 + 克数 */
    private record Portion(Food food, int gram) {
    }

    /**
     * 排饮食（5 天）。
     *
     * @param dietPlanId 草稿饮食计划 id
     * @param userId     当前用户（自定义食物也能被排进来）
     * @param budget     每日热量预算（来自 {@code NutritionCalculator}，本方法不重算）
     */
    public List<DietPlanDetailVO.DayVO> composeDiet(Long dietPlanId, Long userId,
                                                    AiSkeletonNormalizer.Skeleton skeleton,
                                                    int budget, List<String> notes) {
        FoodPool pool = loadFoodPool(userId);
        if (pool.staples().isEmpty() || pool.proteins().isEmpty()) {
            // 食物库空到连"主食 + 蛋白"都凑不出：不硬编，直接报可读的错
            throw new IllegalStateException("食物库里没有可用的主食/蛋白，先执行 sql/fm_plan.sql 或导入食物库");
        }

        List<DietPlanDetailVO.DayVO> dayVos = new ArrayList<>(DIET_CYCLE_DAYS);
        for (int d = 1; d <= DIET_CYCLE_DAYS; d++) {
            DietPlanDay day = new DietPlanDay()
                    .setDietPlanId(dietPlanId)
                    .setDayIndex(d)
                    .setKcalTotal(0);
            dietPlanDayMapper.insert(day);

            List<DietPlanDetailVO.MealVO> mealVos = new ArrayList<>(skeleton.mealSplit().size());
            int dayKcal = 0;
            BigDecimal dayP = BigDecimal.ZERO;
            BigDecimal dayC = BigDecimal.ZERO;
            BigDecimal dayF = BigDecimal.ZERO;

            for (AiSkeletonNormalizer.Slot slot : skeleton.mealSplit()) {
                int targetKcal = (int) Math.round(budget * slot.ratio());
                List<Portion> portions = buildPortions(pool, slot.mealType(), d, slot.mealIndex(), targetKcal);

                int mealKcal = 0;
                List<DietPlanDetailVO.ItemVO> itemVos = new ArrayList<>(portions.size());
                DietPlanMeal meal = new DietPlanMeal()
                        .setDietPlanDayId(day.getId())
                        .setMealType(slot.mealType())
                        .setMealIndex(slot.mealIndex())
                        .setMealTime(timeOf(slot.mealType(), slot.mealIndex()))
                        .setKcal(0);
                dietPlanMealMapper.insert(meal);

                for (int si = 0; si < portions.size(); si++) {
                    Portion p = portions.get(si);
                    BigDecimal gram = BigDecimal.valueOf(p.gram());
                    int kcal = NutritionCalculator.kcalOf(p.food().getKcalPer100g(), gram);
                    mealKcal += kcal;
                    dayP = dayP.add(NutritionCalculator.nutrientOf(p.food().getProteinPer100g(), gram));
                    dayC = dayC.add(NutritionCalculator.nutrientOf(p.food().getCarbPer100g(), gram));
                    dayF = dayF.add(NutritionCalculator.nutrientOf(p.food().getFatPer100g(), gram));

                    dietPlanMealItemMapper.insert(new DietPlanMealItem()
                            .setMealId(meal.getId())
                            .setFoodId(p.food().getId())
                            .setSort(si + 1)
                            .setAmount(gram)
                            .setUnit("克")
                            .setKcal(kcal));
                    itemVos.add(toItemVo(p.food(), gram, kcal));
                }
                dietPlanMealMapper.updateById(new DietPlanMeal().setId(meal.getId()).setKcal(mealKcal));
                dayKcal += mealKcal;
                mealVos.add(toMealVo(meal, mealKcal, itemVos));
            }

            // ★ 整天才提示一次偏差：每餐都提示会把 notes 刷成一屏，用户直接不看了
            if (budget > 0) {
                double deviation = Math.abs(dayKcal - budget) * 1.0 / budget;
                if (deviation > 0.10) {
                    notes.add(String.format("第 %d 天只能排到 %d 千卡（目标 %d），食物库品类有限，差 %.0f%%",
                            d, dayKcal, budget, deviation * 100));
                }
            }

            day.setKcalTotal(dayKcal)
                    .setProteinG(scale1(dayP))
                    .setCarbG(scale1(dayC))
                    .setFatG(scale1(dayF));
            dietPlanDayMapper.updateById(day);

            DietPlanDetailVO.DayVO dayVo = new DietPlanDetailVO.DayVO();
            dayVo.setDayIndex(d);
            dayVo.setKcalTotal(dayKcal);
            dayVo.setProteinG(day.getProteinG());
            dayVo.setCarbG(day.getCarbG());
            dayVo.setFatG(day.getFatG());
            dayVo.setMeals(mealVos);
            dayVos.add(dayVo);
        }
        return dayVos;
    }

    /**
     * 一餐的食物组合（主食 + 蛋白 + 蔬果/奶豆），并调整份量逼近目标热量。
     *
     * <p>★ 只调<b>主食</b>的克数（±10g 步长）：主食是最"可伸缩"的一项，
     * 调鸡胸肉会让"100g 鸡胸"变成"63g 鸡胸"这种不像人写的数字。</p>
     *
     * <p>★ 偏差超过 10% 也不硬凑：宁可少给一份，并在整天层面写进 notes。</p>
     */
    private List<Portion> buildPortions(FoodPool pool, int mealType, int dayIndex, int mealIndex,
                                        int targetKcal) {
        List<Portion> portions = new ArrayList<>(3);
        // ★ 三顿加餐要错开：都取同一个 (day-1) 的话，早/午/晚三次加餐会是"同一杯奶 + 同一个苹果"
        int rotate = dayIndex - 1 + Math.max(0, mealIndex - 1);

        switch (mealType) {
            case DietConstants.MEAL_BREAKFAST -> {
                // 早餐：主食 100g + 蛋白（蛋/奶）50~250g + 奶 250g
                addPortion(portions, pick(pool.staples(), rotate), 100);
                addPortion(portions, pick(pool.proteins(), rotate), 50);
                addPortion(portions, pick(pool.dairy(), rotate), 250);
            }
            case DietConstants.MEAL_LUNCH -> {
                addPortion(portions, pick(pool.staples(), rotate), 150);
                addPortion(portions, pick(pool.proteins(), rotate + 1), 100);
                addPortion(portions, pick(pool.vegetables(), rotate), 150);
            }
            case DietConstants.MEAL_DINNER -> {
                addPortion(portions, pick(pool.staples(), rotate + 2), 120);
                addPortion(portions, pick(pool.proteins(), rotate + 2), 100);
                addPortion(portions, pick(pool.vegetables(), rotate + 1), 150);
            }
            default -> {
                // 加餐：奶 + 水果；没有奶就用坚果 + 水果
                Food dairy = pick(pool.dairy(), rotate);
                if (dairy != null) {
                    addPortion(portions, dairy, 150);
                } else {
                    addPortion(portions, pick(pool.nuts(), rotate), 20);
                }
                addPortion(portions, pick(pool.fruits(), rotate), 120);
            }
        }

        // 把总热量拉到目标（±10% 以内就停）。
        // ★ 优先缩放"主食"；加餐没有主食（奶 + 水果），就缩放热量最大的那一项。
        //   不做这一步的话，加餐会稳定高出 50%（目标 107 千卡，实际 162）——
        //   三顿加餐一起偏，一天就多出 160 千卡，而单看每一餐都"不算离谱"。
        int scaleIdx = indexOfStaple(portions);
        if (scaleIdx < 0) {
            scaleIdx = indexOfMaxKcal(portions);
        }
        if (scaleIdx >= 0 && targetKcal > 0) {
            boolean staple = "主食".equals(portions.get(scaleIdx).food().getCategory());
            int min = staple ? 40 : 30;
            int max = staple ? 400 : 300;
            for (int guard = 0; guard < 8; guard++) {
                int total = totalKcal(portions);
                if (total > 0 && Math.abs(total - targetKcal) * 1.0 / targetKcal <= 0.10) {
                    break;
                }
                Portion cur = portions.get(scaleIdx);
                int next = (int) Math.round(cur.gram() * (targetKcal * 1.0 / Math.max(1, total)) / 10.0) * 10;
                next = Math.max(min, Math.min(max, next));
                if (next == cur.gram()) {
                    break;
                }
                portions.set(scaleIdx, new Portion(cur.food(), next));
            }
        }
        return portions;
    }

    private int indexOfStaple(List<Portion> portions) {
        for (int i = 0; i < portions.size(); i++) {
            if ("主食".equals(portions.get(i).food().getCategory())) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfMaxKcal(List<Portion> portions) {
        int idx = -1;
        int best = -1;
        for (int i = 0; i < portions.size(); i++) {
            int kcal = NutritionCalculator.kcalOf(portions.get(i).food().getKcalPer100g(),
                    BigDecimal.valueOf(portions.get(i).gram()));
            if (kcal > best) {
                best = kcal;
                idx = i;
            }
        }
        return idx;
    }

    private void addPortion(List<Portion> portions, Food food, int gram) {
        if (food != null) {
            portions.add(new Portion(food, gram));
        }
    }

    private int totalKcal(List<Portion> portions) {
        int sum = 0;
        for (Portion p : portions) {
            sum += NutritionCalculator.kcalOf(p.food().getKcalPer100g(), BigDecimal.valueOf(p.gram()));
        }
        return sum;
    }

    /** 按天轮换取一个（列表空返回 null —— 不抛异常，少一个食材而已） */
    private Food pick(List<Food> foods, int index) {
        if (foods == null || foods.isEmpty()) {
            return null;
        }
        return foods.get(Math.floorMod(index, foods.size()));
    }

    private FoodPool loadFoodPool(Long userId) {
        // ★ 按 sort 排序（而不是按 id）：这是"常见位"的同一口径 —— FoodServiceImpl 的
        //   "常见"也是 `ORDER BY sort ASC, id ASC`。
        //   P1 把导入行刻意写成 `sort = 10000 + i`，就是为了让**人工挑的那批**（sort 1~30）排在最前。
        //   不按 sort 排的后果：全量 1638 条后，轮转下标会落到"奶粉""奶油"这类
        //   不能按份吃的食材上，而"250g 一杯奶"这个份量假设只对液态奶成立。
        List<Food> all = foodMapper.selectList(Wrappers.<Food>lambdaQuery()
                .eq(Food::getStatus, 1)
                .and(w -> w.eq(Food::getOwnerId, 0).or().eq(Food::getOwnerId, userId))
                .orderByAsc(Food::getSort)
                .orderByAsc(Food::getId));

        List<Food> staples = new ArrayList<>();
        List<Food> proteins = new ArrayList<>();
        List<Food> vegetables = new ArrayList<>();
        List<Food> dairy = new ArrayList<>();
        List<Food> nuts = new ArrayList<>();
        List<Food> fruits = new ArrayList<>();

        for (Food f : all) {
            String name = f.getName() == null ? "" : f.getName();
            String cat = f.getCategory() == null ? "" : f.getCategory();
            switch (cat) {
                case "主食" -> staples.add(f);
                case "蔬果" -> {
                    // 数据集没有"水果"分类，只能按名字认（不认就归蔬菜，不编）
                    if (FRUIT_WORDS.stream().anyMatch(name::contains)) {
                        fruits.add(f);
                    } else {
                        vegetables.add(f);
                    }
                }
                case "肉蛋奶" -> {
                    if (name.contains("奶") || name.contains("酸奶")) {
                        dairy.add(f);
                    } else {
                        proteins.add(f);
                    }
                }
                case "豆类坚果" -> {
                    if (name.contains("花生") || name.contains("核桃") || name.contains("杏仁")) {
                        nuts.add(f);
                    } else {
                        // 豆腐/豆浆/黄豆：当蛋白用（豆浆也能当早餐的"奶"）
                        if (name.contains("浆")) {
                            dairy.add(f);
                        }
                        proteins.add(f);
                    }
                }
                default -> {
                    // 配料（油/糖）不进计划：没人按"5 克花生油"吃饭
                }
            }
        }
        // ★★ 三处"能按份吃"的门槛（M11-5 §7.4）。
        //
        //   起因：排餐逻辑写死了份量假设 —— 早餐"主食 100g + 蛋白 50g + 奶 250g"。
        //   30 条种子时这些假设都成立（米面、鸡胸、牛奶）；1638 条时池子里混进了
        //   奶粉（478）、奶油（350）、油面筋（492）、油饼（403）……
        //   实测后果：第 4 天早餐 1296 千卡（目标 536），一周偏差 +43.5%，
        //   而"只调主食克数"救不回来（它被夹在 40~400g 之间）。
        //
        //   ① 主食：排掉低密度（马铃薯 77 / 玉米 112 —— 一顿要吃 600g，那不是"一餐"是"一盆"），
        //      再按 sort 取前 5（不再按密度降序：全量后密度最高的恰好是油面筋/油饼这种油炸点心）。
        //   ② 奶：只留 ≤100 千卡/100g —— "250g" 是"一杯"的量，奶粉/奶油/黄油不能按杯算。
        //   ③ 其余池子不动：轮转下标最大 6，只会用到 sort 最前的几条（人工挑的）。
        //
        //   ★ 这三条**不改算法、不加权重**，只是把"份量假设"重新变成真的；
        //     30 条种子下池子与改动前完全一致（实测偏差 5.8% → 5.5%，同量级）。
        List<Food> dense = new ArrayList<>(staples.size());
        for (Food f : staples) {
            Integer kcal = f.getKcalPer100g();
            if (kcal != null && kcal >= 200) {
                dense.add(f);
            }
        }
        if (dense.isEmpty()) {
            dense = staples;   // 库里全是低密度主食时，宁可给低密度也别"没主食"
        }
        List<Food> denseStaples = dense.size() > 5 ? new ArrayList<>(dense.subList(0, 5)) : dense;

        List<Food> liquidDairy = new ArrayList<>(dairy.size());
        for (Food f : dairy) {
            Integer kcal = f.getKcalPer100g();
            if (kcal != null && kcal <= 100) {
                liquidDairy.add(f);
            }
        }
        if (liquidDairy.isEmpty()) {
            liquidDairy = dairy;
        }

        return new FoodPool(denseStaples, proteins, vegetables, liquidDairy, nuts, fruits);
    }

    // ==================== VO 组装 ====================

    private DietPlanDetailVO.MealVO toMealVo(DietPlanMeal meal, int kcal,
                                             List<DietPlanDetailVO.ItemVO> items) {
        DietPlanDetailVO.MealVO vo = new DietPlanDetailVO.MealVO();
        vo.setMealType(meal.getMealType());
        vo.setMealIndex(meal.getMealIndex());
        // ★ 与饮食详情页同一个实现（DietConstants.labelOf），别在这里再写一份
        vo.setLabel(DietConstants.labelOf(meal.getMealType(), meal.getMealIndex()));
        vo.setMealTime(formatTime(meal.getMealTime()));
        vo.setKcal(kcal);
        vo.setItems(items);
        return vo;
    }

    private DietPlanDetailVO.ItemVO toItemVo(Food food, BigDecimal gram, int kcal) {
        DietPlanDetailVO.ItemVO vo = new DietPlanDetailVO.ItemVO();
        vo.setFoodId(food.getId());
        vo.setFoodName(food.getName());
        vo.setEmoji(food.getEmoji());
        vo.setAmount(gram);
        vo.setUnit("克");
        vo.setGram(gram);
        vo.setKcal(kcal);
        vo.setAmountText(stripZeros(gram) + "g");
        return vo;
    }

    /** 餐次时间：与参考 App 一致（早 7:30 / 午 12:00 / 晚 18:30 / 加餐 10:00·15:30·21:00） */
    private LocalTime timeOf(int mealType, int mealIndex) {
        return switch (mealType) {
            case DietConstants.MEAL_BREAKFAST -> LocalTime.of(7, 30);
            case DietConstants.MEAL_LUNCH -> LocalTime.of(12, 0);
            case DietConstants.MEAL_DINNER -> LocalTime.of(18, 30);
            default -> switch (mealIndex) {
                case 1 -> LocalTime.of(10, 0);
                case 2 -> LocalTime.of(15, 30);
                default -> LocalTime.of(21, 0);
            };
        };
    }

    private String formatTime(LocalTime time) {
        return time == null ? null : String.format("%02d:%02d", time.getHour(), time.getMinute());
    }

    private String stripZeros(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private BigDecimal scale1(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP);
    }
}
