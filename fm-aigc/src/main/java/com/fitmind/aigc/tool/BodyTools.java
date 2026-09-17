package com.fitmind.aigc.tool;

import com.fitmind.aigc.service.BodyProfileService;
import com.fitmind.api.dto.user.UserBodyProfileDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 用户身体档案工具（★ 跨模块协同的亮点）。
 *
 * <p><b>★★ 这个类里最值得看的是"没有"的东西：没有任何方法接收 {@code userId} 参数。</b>
 * 查询对象永远是"当前登录用户"，而当前用户只有一个来源 ——
 * 服务端从请求上下文（{@code UserContext}）里拿到的身份。</p>
 *
 * <p>对比常见实现的做法：它用 {@code ToolContext} 把 {@code userId} 传进工具，
 * 方向是对的（说明它意识到了问题），但工具的签名里仍然存在这个参数，
 * 于是<b>还是一条"模型提供一个 id、服务端照着查"的通道</b>。
 * 我们把它彻底关掉：</p>
 *
 * <pre>
 * // ❌ 参数里带 userId —— 模型说查谁就查谁
 * queryBodyProfile(Long userId)
 *
 * // ✅ 参数里没有 —— 模型就算"编"了一个 id，也没有地方放
 * queryMyBodyProfile()
 * </pre>
 *
 * <p>这就是"<b>用结构解决问题，而不是用校验解决问题</b>"：
 * 校验总有漏的时候（大小写、编码、空白、类型强转），
 * 而"根本没有这个入口"不需要校验。同类做法在本项目里已经用过两次 ——
 * 判分接口不接受前端传的答案、退款金额不接受前端传的金额。</p>
 */
@Slf4j
public class BodyTools {

    private final BodyProfileService bodyProfileService;
    private final ToolTrace trace;
    /** 本轮已经拉取过的档案（编排层拉的，与 RAG 过滤用的是同一份） */
    private final UserBodyProfileDTO profile;
    private final String requestId;

    public BodyTools(BodyProfileService bodyProfileService, UserBodyProfileDTO profile,
                     ToolTrace trace, String requestId) {
        this.bodyProfileService = bodyProfileService;
        this.profile = profile;
        this.trace = trace;
        this.requestId = requestId;
    }

    @Tool(name = "queryMyBodyProfile", description = """
            查询【当前登录用户自己】的健身档案：身高、体重、BMI、体脂率、目标体重、训练目标、
            训练水平、每周可训练次数、伤病史。
            不需要任何参数 —— 查询对象永远是当前登录用户，无法查询别人。
            在需要给用户个性化建议（推荐课程、算摄入量、判断训练强度）之前调用它。""")
    public String queryMyBodyProfile() {
        if (!trace.tryAcquire()) {
            return "本轮工具调用次数已达上限，请直接基于已有信息回答，不要再调用工具。";
        }
        long start = System.currentTimeMillis();
        try {
            // ★ 用编排层已经取到的那一份，不重新 Feign 一次：
            //   同一请求里"RAG 过滤用的档案"和"模型看到的档案"必须是同一份，
            //   各自查询会出现"过滤用旧的、模型看新的"这种极难发现的不一致
            String text = bodyProfileService.describe(profile);
            trace.record("queryMyBodyProfile", "{}", brief(text), System.currentTimeMillis() - start, false, null);
            log.info("工具调用[queryMyBodyProfile]：requestId={}，命中档案={}", requestId, profile != null);
            return text;
        } catch (Exception e) {
            // ★ 工具异常一律转成"可读的失败信息"返回给模型，而不是抛出去打断对话。
            //   理由：模型拿到"查询失败"还能换个方式答；抛异常用户只看到系统错误
            trace.record("queryMyBodyProfile", "{}", "查询失败", System.currentTimeMillis() - start, false, e.getMessage());
            log.warn("工具执行失败[queryMyBodyProfile]：{}", e.getMessage());
            return "查询健身档案失败，请稍后再试，或让用户到「我的 → 身体档案」查看。";
        }
    }

    private String brief(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ");
    }
}
