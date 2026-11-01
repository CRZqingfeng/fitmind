import com.fitmind.aigc.tool.CourseTools;
import com.fitmind.aigc.tool.OrderTools;
import com.fitmind.aigc.tool.ToolTrace;

import java.util.List;
import java.util.Map;

/**
 * M6-8 #9 探针：**工具被限流拦下时，轨迹里到底有没有留痕**。
 *
 * <p><b>为什么必须单独探这一条</b>：正常一轮对话里，mock 通道只会调 1~2 次工具，
 * 而单轮上限是 {@code maxCalls}（默认远大于 2）—— 也就是说
 * <b>"被拦"这个分支在真实对话里根本触发不了</b>。
 * 直接写一条"面板上没有死标签"的验收断言，会<b>因为从来没拦过而必然通过</b>（假绿）。</p>
 *
 * <p>所以这里把<b>纯逻辑</b>拉出来单独跑：{@code ToolTrace} 的限额是<b>构造参数</b>
 * （{@code new ToolTrace(maxCalls)}），给 1 就能可靠地造出"第 2 次被拦"。</p>
 *
 * <p>★ 另一个必须用探针的理由：要断言的核心是"<b>被拦的分支有没有写 trace</b>"。
 * 这件事只能看 {@code ToolTrace} 里的记录；走 HTTP 的话得先把面板数据序列化出来，
 * 中间隔着 SSE、JSON、前端组件三层，任何一层漏字段都会让断言失去焦点。</p>
 *
 * <p>★ 只依赖 {@code fm-aigc/target/classes}，不起服务、不连中间件。</p>
 */
public class ToolBlockedProbe {

    private static int failed = 0;
    private static int total = 0;

    public static void main(String[] args) {
        // ============================================================
        // 把额度**预先用光** —— 这样下面的工具调用一定会走"被拦"分支。
        //
        // ★ 为什么用"预先用光"而不是"调两次真的工具"：真正的工具方法会去调
        //   Feign 客户端，而这里给的是 null。被拦的分支**碰都不碰那些依赖**
        //   —— 这本身就是一条值得断言的结论（"拦下来就不该继续干活"）。
        // ============================================================
        ToolTrace trace = new ToolTrace(1);
        trace.tryAcquire(); // 唯一的额度，用掉

        // 依赖全传 null：只要被拦的分支碰了它们，就会 NPE —— 立刻暴露
        CourseTools courses = new CourseTools(null, null, trace, "probe-req", 1L, "probe-token");
        OrderTools orders = new OrderTools(null, null, trace, "probe-req", 1L, "probe-token");

        System.out.println("=== #9 工具超限被拦下时，轨迹里有没有留痕 ===\n");

        // ---- 1. 三个被 @Tool 标注的方法，被拦时都不能抛异常 ----
        String r1 = courses.searchCourses("哑铃");
        String r2 = courses.queryCourseDetail(535L);
        String r3 = orders.prePlaceOrder(List.of(535L));

        check("被拦时 searchCourses 返回可读提示（没有去碰 null 依赖）",
                r1 != null && r1.contains("已达上限"), "返回=" + r1);
        check("被拦时 queryCourseDetail 返回可读提示", r2 != null && r2.contains("已达上限"), "返回=" + r2);
        check("被拦时 prePlaceOrder 返回可读提示", r3 != null && r3.contains("已达上限"), "返回=" + r3);

        // ---- 2. 三次"被拦"都要在轨迹里留下 blocked 记录（#9 的正题）----
        Map<String, Object> payload = trace.toMap();
        List<Map<String, Object>> invocations = castList(payload.get("invocations"));
        List<Map<String, Object>> blocked = invocations.stream()
                .filter(i -> Boolean.TRUE.equals(i.get("blocked"))).toList();

        // ★ 这条断言如果为 0，说明"被拦"这件事在面板上完全不可见
        //   —— CoachPanel 里那个"超出调用次数上限被拦下"的橙标就是永不出现的死 UI
        check("★★ 三次被拦**各留了一条 blocked 记录**（面板上的「超限拦截」标签才有数据源）",
                blocked.size() == 3, "blocked 记录数=" + blocked.size() + " / 全部=" + invocations.size());

        List<String> blockedNames = blocked.stream().map(i -> String.valueOf(i.get("name"))).toList();
        check("★★ 三条记录分别对应三个工具（不是同一条被重复计）",
                blockedNames.containsAll(List.of("searchCourses", "queryCourseDetail", "prePlaceOrder")),
                "被拦的工具=" + blockedNames);

        check("★ 每条都带真实原因（面板/排查能直接读到「为什么被拦」）",
                blocked.stream().allMatch(i -> i.get("error") != null && String.valueOf(i.get("error")).contains("上限")),
                "error 字段=" + blocked.stream().map(i -> String.valueOf(i.get("error"))).toList());

        // ---- 3. toolNames 也要带上（前端"这一轮调了哪些工具"这一栏靠它）----
        check("★ 被拦的调用也出现在 toolNames 里（否则「调过但被拦」与「根本没调」看起来一样）",
                castList(payload.get("toolNames")).containsAll(List.of("searchCourses", "queryCourseDetail", "prePlaceOrder")),
                "toolNames=" + payload.get("toolNames"));

        // ---- 4. ★ 反证：没超限时**不该**有 blocked 记录（免得"永远 true"变成假绿）----
        ToolTrace ok = new ToolTrace(5);
        CourseTools okCourses = new CourseTools(null, null, ok, "probe-req2", 1L, "probe-token");
        ok.tryAcquire(); // 用掉一次，但还剩 4 次
        // 这里不能真调工具（依赖是 null、而且会真的执行）—— 只断言"额度没用完时 tryAcquire 为真"
        check("★ 反证：额度没用完时 tryAcquire() 为真（说明上面那批 blocked 是「真超限」而不是分支写错）",
                ok.tryAcquire(), "第 2 次 tryAcquire（上限 5）");
        check("★ 反证：没被拦的轨迹里**没有** blocked 记录",
                castList(ok.toMap().get("invocations")).isEmpty(),
                "invocations=" + ok.toMap().get("invocations"));

        System.out.println("\n" + (failed == 0 ? "✅ 全部通过" : "❌ 有失败")
                + "：" + (total - failed) + " / " + total);
        if (failed > 0) {
            System.exit(1);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object o) {
        return o == null ? List.of() : (List<Map<String, Object>>) o;
    }

    private static void check(String name, boolean ok, String detail) {
        total++;
        if (!ok) {
            failed++;
        }
        System.out.println((ok ? "  ✅ " : "  ❌ ") + name + (ok ? "" : "  —— " + detail));
    }
}
