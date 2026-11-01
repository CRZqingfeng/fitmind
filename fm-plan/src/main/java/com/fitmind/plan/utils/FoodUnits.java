package com.fitmind.plan.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 食物单位 JSON 的读写（{@code food.units_json}）。
 *
 * <p><b>为什么不做"任意单位换算引擎"</b>（口径 7）：数据里只有"一个默认单位 + 最多两个常用单位"，
 * 做一个万能换算器的代价是：某些食物会被填成"1 碗 = 1 克"，页面上完全看不出来，
 * 只有"记了一碗饭结果热量是 3 千卡"这种诡异现象。这里只认 JSON 里列出的单位，查不到就报错。</p>
 *
 * <p>格式：{@code [{"unit":"克","gram":1},{"unit":"碗","gram":150}]}</p>
 */
@Slf4j
public final class FoodUnits {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Unit>> LIST_TYPE = new TypeReference<>() {
    };

    private FoodUnits() {
    }

    /** 一个单位：unit 名字 + gram = 1 个该单位多少克 */
    public record Unit(String unit, BigDecimal gram) {
    }

    /** 解析成列表；空/null 返回"只有克"的兜底（历史数据里 units_json 可能是 NULL） */
    public static List<Unit> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of(new Unit("克", BigDecimal.ONE));
        }
        try {
            List<Unit> list = MAPPER.readValue(json, LIST_TYPE);
            if (list == null || list.isEmpty()) {
                return List.of(new Unit("克", BigDecimal.ONE));
            }
            return list;
        } catch (Exception e) {
            // 数据损坏要能看出来：直接抛出，不要静默返回"只有克"——
            // 那样"1 碗"会被当成"1 克"，记出来的热量是错的，而页面上毫无异样
            log.error("食物单位 JSON 损坏：{}", json, e);
            throw new IllegalStateException("食物单位数据损坏，请联系管理员重新导入食物库", e);
        }
    }

    /** 单位名 → 每单位克数 */
    public static Map<String, BigDecimal> toMap(String json) {
        Map<String, BigDecimal> map = new LinkedHashMap<>();
        for (Unit u : parse(json)) {
            map.put(u.unit(), u.gram());
        }
        return map;
    }

    /** 组装 JSON（创建自定义食物时用） */
    public static String toJson(List<Unit> units) {
        List<Unit> list = new ArrayList<>();
        // 克永远在第一位（默认单位），且固定 = 1
        list.add(new Unit("克", BigDecimal.ONE));
        if (units != null) {
            for (Unit u : units) {
                if (u.unit() == null || u.unit().isBlank() || "克".equals(u.unit())) {
                    continue;
                }
                if (u.gram() == null || u.gram().signum() <= 0) {
                    continue;
                }
                list.add(u);
            }
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            throw new IllegalStateException("序列化食物单位失败", e);
        }
    }
}
