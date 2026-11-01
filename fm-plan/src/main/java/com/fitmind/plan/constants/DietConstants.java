package com.fitmind.plan.constants;

import java.util.List;

/**
 * 饮食模块常量：餐次、分类、单位、预算系数。
 *
 * <p><b>本类最容易写错的地方</b>：餐次有两套口径，别混：</p>
 * <ul>
 *   <li><b>记录侧</b>（{@code diet_log}）只有 4 个值 —— 用户在首页看到的是 4 个入口
 *       （早/午/晚/加餐），所以这里只有 {@code MEAL_*} 四个常量，<b>没有 meal_index</b>。</li>
 *   <li><b>计划侧</b>（{@code diet_plan_meal}）多一个 {@code meal_index} ——
 *       参考 App 的增肌计划是"每日六餐、三次加餐"，不留 index 就只能插进去一条。</li>
 * </ul>
 *
 * <p>★ 分类字典**不再写常量**：饮食库左侧只展示"库里真有数据的分类"
 * （见 {@code FoodServiceImpl.queryDict} 里的 {@code SELECT DISTINCT category}）。
 * 常量化的话，中式菜肴 / 西式菜肴 这两类在数据集里根本不存在，
 * 用户点进去会得到三个永远空着的入口 —— 那不是"分类"，是三个坑。</p>
 */
public interface DietConstants {

    // ==================== 餐次（记录侧，只有 4 个） ====================
    int MEAL_BREAKFAST = 1;
    int MEAL_LUNCH = 2;
    int MEAL_DINNER = 3;
    int MEAL_SNACK = 4;

    /** 下标 = mealType - 1 */
    List<String> MEAL_LABELS = List.of("早餐", "午餐", "晚餐", "加餐");

    // ==================== 分类排序（固定顺序，保证前端每次看到的顺序一致） ====================
    /** 数据库里 category 的全集，也是**展示顺序**；实际返回哪些由库里 distinct 决定 */
    List<String> FOOD_CATEGORY_ORDER = List.of(
            "主食", "蔬果", "肉蛋奶", "豆类坚果", "零食饮料", "中式菜肴", "西式菜肴", "配料");

    List<String> SPORT_CATEGORY_ORDER = List.of(
            "日常", "走路", "跑步", "骑行", "有氧操", "舞蹈瑜伽",
            "游泳", "球类运动", "跳绳", "爬楼梯", "其他");

    // ==================== 食物库左侧入口（不是分类，是过滤/搜索） ====================
    String SCOPE_COMMON = "common";
    String SCOPE_FAVORITE = "favorite";
    String SCOPE_MINE = "mine";

    // ==================== 预算系数（口径 2：预算由三大营养素反推，只在 NutritionCalculator 一处算） ====================
    /** 增肌：每公斤体重 2.0 g 蛋白 */
    double PROTEIN_G_PER_KG_GAIN = 2.0;
    /** 减脂：每公斤体重 1.6 g 蛋白（掉秤期保肌） */
    double PROTEIN_G_PER_KG_LOSS = 1.6;
    /** 脂肪：每公斤体重 0.8 g */
    double FAT_G_PER_KG = 0.8;
    /** 起算热量：增肌 33 kcal/kg、减脂 28 kcal/kg（仅用于算碳水余量） */
    int KCAL_PER_KG_GAIN = 33;
    int KCAL_PER_KG_LOSS = 28;

    /** 4/4/9 系数 */
    int KCAL_PER_G_PROTEIN = 4;
    int KCAL_PER_G_CARB = 4;
    int KCAL_PER_G_FAT = 9;

    String GOAL_GAIN = "增肌";
    String GOAL_LOSS = "减脂";
}
