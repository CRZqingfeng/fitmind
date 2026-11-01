import com.fitmind.aigc.tool.ToolTrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ToolTrace 卡片治理的输出探针（M6-8 #15）。
 *
 * <p><b>为什么需要它</b>：Mock 通道下一轮对话**只会调一次 `searchCourses`**
 * （{@code MockChatModel.calledToolNames()} 按**工具名**去重，"已经调过的工具不再调"），
 * 所以"一轮里出现多张课程卡片"这个场景在 mock 下**根本触发不了**。</p>
 *
 * <p>★ 那么如果验收直接断言"卡片不超过 1 张"，它会**因为只搜了一次而必然通过** ——
 * <b>这是假绿</b>（"期望为 1"没有证明"前置动作真的能产生多张"）。
 * 而真实模型下这件事又是非确定的。</p>
 *
 * <p>→ 所以把去重逻辑**单独拉出来验证**：直接 new 一个 ToolTrace、喂两张卡片，
 * 看它到底合并没合并。<b>纯逻辑、无依赖、确定性。</b></p>
 *
 * <p>★ 为了能被"输出即断言"地解析（和别的验收脚本一个套路），这里只打印
 * {@code KEY=value} 形式的行，不在 Java 里做断言 —— 断言留在验收脚本里，
 * 这样"判据在哪"永远只有一处。</p>
 */
public class ToolTraceProbe {

    public static void main(String[] args) {
        case1CourseListMerged();
        case2OrderConfirmKeepsLast();
        case3OtherTypesUntouched();
    }

    /** ① 两次搜索 → 合并成一张，课程按 courseId 去重，关键词累加 */
    private static void case1CourseListMerged() {
        ToolTrace trace = new ToolTrace(6);
        trace.addCard(courseList("哑铃", 535L, 536L));
        trace.addCard(courseList("增肌", 536L, 541L));

        List<Map<String, Object>> cards = trace.getCards();
        System.out.println("CASE1_CARD_COUNT=" + cards.size());
        if (cards.size() != 1) {
            System.out.println("CASE1_COURSE_IDS=");
            System.out.println("CASE1_KEYWORD=");
            return;
        }
        Map<String, Object> card = cards.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> courses = (List<Map<String, Object>>) card.get("courses");
        System.out.println("CASE1_COURSE_IDS=" + courses.stream().map(c -> c.get("courseId")).toList());
        System.out.println("CASE1_KEYWORD=" + card.get("keyword"));
        System.out.println("CASE1_TYPE=" + card.get("type"));
    }

    /** ② 两次下单 → 只保留最后一张（前面那笔已经不该被点了） */
    private static void case2OrderConfirmKeepsLast() {
        ToolTrace trace = new ToolTrace(6);
        trace.addCard(orderConfirm("1001"));
        trace.addCard(orderConfirm("1002"));

        List<Map<String, Object>> cards = trace.getCards();
        System.out.println("CASE2_CARD_COUNT=" + cards.size());
        System.out.println("CASE2_ORDER_ID=" + (cards.isEmpty() ? "" : cards.get(0).get("orderId")));
    }

    /** ③ 其它类型（体测/知识卡片之类）**照旧各自保留** —— 只治该治的，不误伤 */
    private static void case3OtherTypesUntouched() {
        ToolTrace trace = new ToolTrace(6);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("type", "BODY_PROFILE");
        a.put("title", "A");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("type", "BODY_PROFILE");
        b.put("title", "B");
        trace.addCard(a);
        trace.addCard(b);
        System.out.println("CASE3_CARD_COUNT=" + trace.getCards().size());
    }

    private static Map<String, Object> courseList(String keyword, Long... ids) {
        List<Map<String, Object>> courses = new ArrayList<>();
        for (Long id : ids) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("courseId", id);
            c.put("courseName", "课程" + id);
            courses.add(c);
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "COURSE_LIST");
        card.put("title", "为你找到的课程");
        card.put("keyword", keyword);
        card.put("courses", courses);
        return card;
    }

    private static Map<String, Object> orderConfirm(String orderId) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "ORDER_CONFIRM");
        card.put("title", "待支付订单");
        card.put("orderId", orderId);
        return card;
    }
}
