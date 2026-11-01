package com.fitmind.plan.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * 动作库 JSON 字段读写（肌群 / 步骤）。
 *
 * <p>为什么不用 Hutool：fm-plan 为了少一个依赖没引 hutool-all，
 * 而 Spring Web 已经带了 Jackson，再加一个 JSON 库只是多一个版本要跟。</p>
 *
 * <p><b>解析失败为什么抛异常而不是返回空列表</b>：这些字段是导入脚本写进去的，
 * 解析失败说明数据坏了。悄悄返回空列表的话，页面上「主要肌群」一片空白，
 * 没人会去看日志 —— 而一个 {@code IllegalStateException} 会在请求里直接暴露出来。</p>
 */
@Slf4j
public final class PlanJsonUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private PlanJsonUtils() {
    }

    /** List → JSON 字符串；null / 空列表返回 null（库里存 NULL 比存 "[]" 更好读） */
    public static String toJson(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 JSON 失败", e);
        }
    }

    /** JSON 字符串 → List；null / 空串返回空列表 */
    public static List<String> toList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<String> list = MAPPER.readValue(json, STRING_LIST);
            return list == null ? Collections.emptyList() : list;
        } catch (Exception e) {
            log.error("动作库 JSON 字段损坏：{}", json, e);
            throw new IllegalStateException("动作库 JSON 字段损坏，请联系管理员重新导入动作数据", e);
        }
    }
}
