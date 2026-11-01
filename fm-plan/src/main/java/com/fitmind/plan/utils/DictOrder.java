package com.fitmind.plan.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 字典排序：把库里 DISTINCT 出来的值，按"常量里写好的顺序"排。
 *
 * <p><b>为什么要单独一个类：</b>动作字典、食物字典、运动字典都是
 * "`SELECT DISTINCT xxx WHERE status=1` + 按常量顺序排"。三处各写一份的话，
 * 迟早有一处忘了"未列出的追加在后"，于是左侧栏顺序随 MySQL 返回顺序抖动 ——
 * 用户刷新两次页面，左侧栏顺序变了，而他不会报 bug，只会觉得"这 App 有点怪"。</p>
 *
 * <p>★ 规则：出现在 {@code reference} 里的按它的下标排；不在的**追加在后**并按原值排序
 * （保证"同一个库、同样数据"永远得到同样的顺序，可复现、可断言）。</p>
 */
public final class DictOrder {

    private DictOrder() {
    }

    /**
     * @param found     库里真实存在的值（可含 null，会被丢掉）
     * @param reference 排序参考（常量）
     */
    public static List<String> order(List<?> found, List<String> reference) {
        List<String> values = new ArrayList<>(found.size());
        for (Object o : found) {
            if (o != null) {
                String s = String.valueOf(o);
                if (!s.isBlank()) {
                    values.add(s);
                }
            }
        }
        values.sort(Comparator
                .comparingInt((String v) -> {
                    int i = reference.indexOf(v);
                    return i < 0 ? Integer.MAX_VALUE : i;
                })
                // 都不在常量里时按字面排：否则会退化成"MySQL 返回什么顺序就是什么顺序"
                .thenComparing(Comparator.naturalOrder()));
        return values;
    }
}
