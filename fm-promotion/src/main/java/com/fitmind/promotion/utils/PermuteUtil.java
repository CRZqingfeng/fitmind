package com.fitmind.promotion.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * 全排列工具类（回溯）。
 *
 * <p>用在"叠加推荐"里：把 N 张可用券的所有组合方式都列出来，
 * 逐个算优惠金额，再挑最优。</p>
 *
 * <p>⚠️ N 张券的全排列是 <b>N! 阶乘级</b>增长的 ——
 * 所以调用方必须限制参与排列的券数（见
 * {@code promotion.max-permute-coupons}），否则 10 张券就是 360 万个方案。</p>
 */
public class PermuteUtil {

    private PermuteUtil() {
    }

    /**
     * 生成输入集合的所有排列。
     *
     * <p>注意：返回的是<b>排列</b>（顺序不同算不同方案）。
     * 券的顺序会影响"先扣哪张"的结果（因为每张券算完会减少可扣金额），
     * 所以这里确实需要排列而不是组合 —— 这正是"叠加"要穷举的原因。</p>
     */
    public static <T> List<List<T>> permute(List<T> input) {
        List<List<T>> res = new ArrayList<>();
        backtrack(input.size(), new ArrayList<>(input), res, 0);
        return res;
    }

    private static <T> void backtrack(int n, List<T> input, List<List<T>> res, int first) {
        if (first == n) {
            res.add(new ArrayList<>(input));
            return;
        }
        for (int i = first; i < n; i++) {
            swap(input, first, i);
            backtrack(n, input, res, first + 1);
            swap(input, first, i);
        }
    }

    private static <T> void swap(List<T> list, int i, int j) {
        T tmp = list.get(i);
        list.set(i, list.get(j));
        list.set(j, tmp);
    }
}
