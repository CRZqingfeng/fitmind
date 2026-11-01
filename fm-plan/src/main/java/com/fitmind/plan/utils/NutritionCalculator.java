package com.fitmind.plan.utils;

import com.fitmind.plan.constants.DietConstants;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 热量预算与三大营养素目标 —— 全模块**唯一**的计算入口。
 *
 * <p><b>最容易写错的地方</b>：先按体重算出 kcal，再分别算 P/C/F ——
 * 那样三个数加起来（4P+4C+9F）几乎必然和 kcal 差 1~3 千卡，
 * 于是详情页会出现"预算 2268 千卡，但 130×4+324×4+50×9=2266"这种自相矛盾的画面。</p>
 *
 * <p><b>本类的做法（口径 2）</b>：</p>
 * <pre>
 * 1) 蛋白 = 体重 × 2.0(增肌) / 1.6(减脂)          → 保留 1 位小数
 * 2) 脂肪 = 体重 × 0.8                            → 保留 1 位小数
 * 3) 起算热量 kcal0 = 体重 × 33 / 28              （只用来算碳水余量）
 * 4) 碳水 = max(0, (kcal0 − 4×蛋白 − 9×脂肪) / 4)  → 保留 1 位小数
 * 5) **kcal_budget = round(4×蛋白 + 4×碳水 + 9×脂肪)**
 * </pre>
 *
 * <p>★ 第 5 步是关键：预算由三大营养素**反推**出来，不是独立拍一个数。
 * 这样"预算"和"三大营养素目标"永远是同一件事的两种表述，页面上的进度条加起来正好等于预算。
 * 代价是 kcal 可能比 kcal0 差个位数 —— 这是刻意的，参考 App 的 2266 vs 2268 就是这个关系。</p>
 *
 * <p>★ 为什么用 {@link BigDecimal} 而不是 double：0.1 + 0.2 != 0.3 这种浮点误差，
 * 累积到"预算差 1 千卡"就会被人问"为什么昨天 2268 今天是 2269"。</p>
 */
public final class NutritionCalculator {

    private NutritionCalculator() {
    }

    /**
     * 计算结果。
     *
     * @param kcalBudget 每日热量预算（= 4P+4C+9F，已四舍五入）
     * @param proteinG   蛋白目标（克）
     * @param carbG      碳水目标（克）
     * @param fatG       脂肪目标（克）
     */
    public record MacroPlan(int kcalBudget, BigDecimal proteinG, BigDecimal carbG, BigDecimal fatG) {
    }

    /**
     * 按体重与目标算预算。
     *
     * @param weightKg 体重（kg，库里一律 kg；界面显示"斤"由前端 ×2）
     * @param goal     "增肌" / "减脂"；其他值（含 null）按增肌处理
     */
    public static MacroPlan plan(BigDecimal weightKg, String goal) {
        if (weightKg == null || weightKg.signum() <= 0) {
            return new MacroPlan(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        boolean loss = DietConstants.GOAL_LOSS.equals(goal);
        double proteinPerKg = loss
                ? DietConstants.PROTEIN_G_PER_KG_LOSS
                : DietConstants.PROTEIN_G_PER_KG_GAIN;
        int kcalPerKg = loss ? DietConstants.KCAL_PER_KG_LOSS : DietConstants.KCAL_PER_KG_GAIN;

        BigDecimal protein = scale1(weightKg.multiply(BigDecimal.valueOf(proteinPerKg)));
        BigDecimal fat = scale1(weightKg.multiply(BigDecimal.valueOf(DietConstants.FAT_G_PER_KG)));

        BigDecimal kcal0 = weightKg.multiply(BigDecimal.valueOf(kcalPerKg));
        BigDecimal fromProtein = protein.multiply(BigDecimal.valueOf(DietConstants.KCAL_PER_G_PROTEIN));
        BigDecimal fromFat = fat.multiply(BigDecimal.valueOf(DietConstants.KCAL_PER_G_FAT));
        BigDecimal carbKcal = kcal0.subtract(fromProtein).subtract(fromFat).max(BigDecimal.ZERO);
        BigDecimal carb = scale1(carbKcal.divide(BigDecimal.valueOf(DietConstants.KCAL_PER_G_CARB), 1, RoundingMode.HALF_UP));

        // ★ 预算由 macro 反推（见类注释第 5 步），保证 4P+4C+9F == kcalBudget
        BigDecimal total = fromProtein
                .add(carb.multiply(BigDecimal.valueOf(DietConstants.KCAL_PER_G_CARB)))
                .add(fromFat);
        int kcal = total.setScale(0, RoundingMode.HALF_UP).intValue();
        return new MacroPlan(kcal, protein, carb, fat);
    }

    /**
     * BMI = 体重(kg) / 身高(m)²，保留 1 位小数。
     *
     * <p>身高缺失时返回 null —— <b>不要填 0</b>：前端显示"0.0"比显示"—"误导得多
     * （用户会以为自己 BMI 为 0，而不是"缺数据"）。</p>
     */
    public static BigDecimal bmi(BigDecimal weightKg, BigDecimal heightCm) {
        if (weightKg == null || heightCm == null || heightCm.signum() <= 0) {
            return null;
        }
        BigDecimal heightM = heightCm.divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
        BigDecimal square = heightM.multiply(heightM);
        if (square.signum() == 0) {
            return null;
        }
        return weightKg.divide(square, 1, RoundingMode.HALF_UP);
    }

    /**
     * 一份食物折算成克。
     *
     * <p>★ 这里**不做"任意单位换算引擎"**：只认 {@code units_json} 里列出的单位，
     * 查不到就报错。做一个万能换算器的后果是"1 碗米饭"在某些食物上被算成 1 克，
     * 而页面上看不出来 —— 用户只知道数字不对。</p>
     */
    public static BigDecimal gramOf(BigDecimal amount, String unit, java.util.Map<String, BigDecimal> unitGram) {
        if (amount == null || unit == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal per = unitGram.get(unit);
        if (per == null) {
            return null;
        }
        return amount.multiply(per);
    }

    /** 按"每 100g"单价折算某份量的热量，四舍五入到整千卡（与 MySQL ROUND 同口径） */
    public static int kcalOf(Integer kcalPer100g, BigDecimal gram) {
        if (kcalPer100g == null || gram == null) {
            return 0;
        }
        BigDecimal v = BigDecimal.valueOf(kcalPer100g)
                .multiply(gram)
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        return v.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /** 按"每 100g"营养值折算某份量的克数（蛋白/碳水/脂肪共用） */
    public static BigDecimal nutrientOf(BigDecimal per100g, BigDecimal gram) {
        if (per100g == null || gram == null) {
            return BigDecimal.ZERO;
        }
        return per100g.multiply(gram).divide(BigDecimal.valueOf(100), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale1(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP);
    }
}
