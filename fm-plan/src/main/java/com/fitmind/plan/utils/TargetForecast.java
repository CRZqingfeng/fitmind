package com.fitmind.plan.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 目标推算 —— 从"现在多少公斤"到"目标多少公斤，大概什么时候到"。
 *
 * <p><b>本类最容易写错的地方：{@code daysToGoal} 的量纲。</b></p>
 *
 * <p>方案初稿写的是 {@code daysToGoal = ceil(|Δ| / weeklyDelta)}，
 * 而 {@code weeklyDelta} 的单位是 <b>kg / 周</b> —— 除出来是"周数"，不是"天数"。
 * 后果很直观：65kg → 75kg、每周 0.25kg，会被算成 <b>40 天</b>
 * （等于每周涨 1.75kg，比 0.75 的上限还高一倍多），
 * 而同一页面的 7 天折线只有 +0.2kg —— <b>同一屏里两个数字在讲两个故事</b>。</p>
 *
 * <p>所以这里 <b>乘 7</b>：{@code daysToGoal = ceil(|Δ| / weeklyDelta × 7)}。
 * 65 → 75 是 280 天（每周 0.25kg，自然增肌的真实速度）。</p>
 *
 * <p>★ 为什么不把这个公式放进 {@code NutritionCalculator}：
 * 那边算的是<b>热量</b>（4P+4C+9F 的唯一实现），这边算的是<b>时间</b>，
 * 两者没有共享的中间量。硬塞在一起只会让"预算公式"那个类越来越难读。</p>
 *
 * <p>★ 为什么是纯静态工具类、不注入任何 Bean：它没有任何外部依赖
 * （不查库、不调服务），做成纯函数才能被单测和验收脚本<b>用同一套公式复算</b> ——
 * 脚本里的期望值必须是"按公式算"，而不是"抄接口返回的数"。</p>
 */
public final class TargetForecast {

    /** 每周变化下限（kg）：小目标也要按"至少这么多"来推，否则会推出"3 天达成 5kg" */
    private static final BigDecimal MIN_WEEKLY = new BigDecimal("0.25");
    /** 每周变化上限（kg）：防"7 天瘦 20 斤" */
    private static final BigDecimal MAX_WEEKLY = new BigDecimal("0.75");
    /** 每周变化先按体重的 1% 估 */
    private static final BigDecimal WEEKLY_PERCENT = new BigDecimal("0.01");
    /** 折线点数：今天 + 未来 6 天 */
    public static final int FORECAST_DAYS = 7;

    private TargetForecast() {
    }

    /**
     * 折线上的一个点。
     *
     * @param date     日期
     * @param weightKg 那天预计的体重（1 位小数）
     */
    public record Point(LocalDate date, BigDecimal weightKg) {
    }

    /**
     * 推算结果。
     *
     * @param weeklyDeltaKg 每周变化量（2 位小数）
     * @param daysToGoal    还要多少天（null = 没法定，比如缺目标体重）
     * @param targetDate    预计达成日（null = 同上）
     * @param forecast      7 天折线（永远 7 个点，缺数据时是水平线）
     */
    public record Result(BigDecimal weeklyDeltaKg, Integer daysToGoal, LocalDate targetDate,
                         List<Point> forecast) {
    }

    /**
     * 推算。
     *
     * @param currentKg 当前体重（kg）；null → 全部返回 null（前端引导去记录体重）
     * @param targetKg  目标体重（kg）；null → 只给水平折线，不猜方向
     * @param today     基准日（由调用方传，便于验收脚本用固定日期复算）
     */
    public static Result compute(BigDecimal currentKg, BigDecimal targetKg, LocalDate today) {
        LocalDate base = today == null ? LocalDate.now() : today;

        if (currentKg == null) {
            return new Result(null, null, null, List.of());
        }
        // ★ 目标体重缺失时**不要**瞎猜一个数（比如"减 5% 体重"）：
        //   那会让用户看到一条"系统替我定的目标"，而他从来没填过。
        if (targetKg == null) {
            return new Result(null, null, null, flat(currentKg, base, 0));
        }

        BigDecimal diff = targetKg.subtract(currentKg).abs();
        if (diff.signum() == 0) {
            // 当前 = 目标：0 天、折线水平。★ 这里必须显式返回，
            // 否则下面的除法会除 0（BigDecimal 会抛 ArithmeticException，接口直接 500）
            return new Result(BigDecimal.ZERO, 0, base, flat(currentKg, base, 0));
        }

        BigDecimal weekly = clamp(diff.multiply(WEEKLY_PERCENT));
        // ★ ×7：weekly 的单位是"每周"，乘 7 才是天
        int days = (int) Math.ceil(diff.divide(weekly, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(7)).doubleValue());

        int sign = targetKg.compareTo(currentKg) > 0 ? 1 : -1;
        BigDecimal dailyStep = weekly.divide(BigDecimal.valueOf(7), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(sign));
        return new Result(scale2(weekly), days, base.plusDays(days), flat(currentKg, base, dailyStep.doubleValue()));
    }

    /** 折线：{@code start + step × i}，i = 0..6；step = 0 时就是一条水平线 */
    private static List<Point> flat(BigDecimal start, LocalDate base, double step) {
        List<Point> points = new ArrayList<>(FORECAST_DAYS);
        BigDecimal stepDec = BigDecimal.valueOf(step);
        for (int i = 0; i < FORECAST_DAYS; i++) {
            BigDecimal w = start.add(stepDec.multiply(BigDecimal.valueOf(i)));
            points.add(new Point(base.plusDays(i), w.setScale(1, RoundingMode.HALF_UP)));
        }
        return points;
    }

    private static BigDecimal clamp(BigDecimal weekly) {
        return weekly.max(MIN_WEEKLY).min(MAX_WEEKLY);
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
