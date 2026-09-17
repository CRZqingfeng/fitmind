package com.fitmind.aigc.controller;

import com.fitmind.aigc.limit.ChatQuotaLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 限流管理端接口。
 *
 * <p>走 {@code /admin/**} 前缀（由 {@code AdminAuthInterceptor} 校验管理员身份），
 * 网关侧完整路径 {@code /ais/admin/limit/**}。</p>
 *
 * <p>★★ <b>为什么一个"限额"需要管理端接口</b>：
 * 限额不调小就永远验证不到"被限住"的行为 —— 一天 200 次的额度要打满 200 轮才测得到，
 * 而"改 yml + 重启"又会把"调额度是否需要重启"这个问题变得无法回答。
 * 所以给一个<b>临时调额入口</b>：验收能在几秒内测出限流，
 * 运维也能在活动期间临时放宽（这是它的真实用途，不是为测试造的假接口）。</p>
 *
 * <p>⚠️ 临时额度<b>刻意不持久化</b>（重启恢复配置值）：临时调额是一次操作意图，
 * 不是长期配置 —— 落库的话，一次误操作就永久生效，而且没人会想起来改回去。</p>
 */
@Slf4j
@RestController
@RequestMapping("/admin/limit")
@RequiredArgsConstructor
@Tag(name = "AI 限流管理端")
public class LimitAdminController {

    private final ChatQuotaLimiter quotaLimiter;

    /**
     * 查看某个用户当前的配额状态（含临时额度）。
     */
    @Operation(summary = "查看某个用户的今日配额")
    @GetMapping
    public Map<String, Object> view(@RequestParam Long userId) {
        ChatQuotaLimiter.QuotaView v = quotaLimiter.view(userId);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", v.enabled());
        map.put("usedChats", v.usedChats());
        map.put("dailyChats", v.dailyChats());
        map.put("usedTokens", v.usedTokens());
        map.put("dailyTokens", v.dailyTokens());
        map.put("running", v.running());
        map.put("maxConcurrent", v.maxConcurrent());
        return map;
    }

    /**
     * 临时调额。
     *
     * <p>★ 三类额度必须能分开调：验收要能单独验证"超轮数"与"超 token"是两条不同的路径
     * （提示文案不同、触发条件不同）。如果只能整体调，就没法构造
     * "轮数还很多但 token 用完了"这种场景。</p>
     */
    @Operation(summary = "临时调额（不持久化，重启恢复）")
    @PostMapping
    public Map<String, Object> override(@RequestParam int dailyChats,
                                        @RequestParam int dailyTokens,
                                        @RequestParam(defaultValue = "2") int maxConcurrent) {
        quotaLimiter.override(dailyChats, dailyTokens, maxConcurrent);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dailyChats", dailyChats);
        result.put("dailyTokens", dailyTokens);
        result.put("maxConcurrent", maxConcurrent);
        result.put("note", "只影响本进程，重启后恢复配置值");
        return result;
    }

    /** 恢复配置额度 */
    @Operation(summary = "恢复为配置额度")
    @DeleteMapping
    public boolean resetOverride() {
        quotaLimiter.resetOverride();
        return true;
    }

    /**
     * 清空某个用户当天的计数。
     *
     * <p>★ 验收与演示都需要它：否则"验证被打满之后会怎样"这件事就变成
     * <b>必须等到第二天</b>—— 那等于这个行为永远不会被验证。</p>
     */
    @Operation(summary = "清空某用户今日计数（验收/演示用）")
    @DeleteMapping("/counter")
    public boolean resetCounter(@RequestParam Long userId) {
        quotaLimiter.reset(userId);
        log.warn("★ 已清空用户 {} 的今日 AI 配额计数", userId);
        return true;
    }
}
