package com.fitmind.aigc.tool;

import com.fitmind.aigc.client.OrderClient;
import com.fitmind.aigc.client.dto.PrePlaceOrderDTO;
import com.fitmind.api.client.course.CourseClient;
import com.fitmind.api.dto.course.CourseSimpleDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 下单工具 —— AI 只能"生成待确认订单"，不能完成支付。
 *
 * <p>★★ <b>三条写死的边界（每一条都对应一个真实风险）</b>：</p>
 * <ol>
 *   <li><b>金额不由模型决定。</b> 工具只传课程 id，价格全部由 fm-trade 从库里算。
 *       模型在对话里说"这门课 1 分钱"没有任何用 —— 它没有把这个数字送进交易的通道。
 *       （沿用 M4-1 的"金额不由前端给"原则，只是这次多了一个更不可信的前端：大模型。）</li>
 *   <li><b>先校验存在性，再调用有副作用的接口。</b> 顺序很重要：
 *       {@code prePlace} 会在交易库里真的落一条待支付订单。如果先调它、失败了再兜错，
 *       就会为了一个不存在的课程产生一条脏订单。<b>把校验放在副作用之前。</b></li>
 *   <li><b>工具不完成支付。</b> 返回的是"待确认卡片"，用户看了金额之后自己点支付。
 *       一个会自己花钱的 AI 不是功能，是事故。</li>
 * </ol>
 *
 * <p>★ 顺带一个产品向的取舍：预下单产生的待支付订单如果用户没支付，
 * 会被 M4-1 的超时取消任务自动关闭，所以<b>不会留下垃圾数据</b> ——
 * 这也是"敢让 AI 调它"的前提。</p>
 */
@Slf4j
public class OrderTools {

    private final OrderClient orderClient;
    private final CourseClient courseClient;
    private final ToolTrace trace;
    private final String requestId;
    /** 调用者身份：由编排层从 UserContext 传来，**不来自模型**（见 ToolAuthContext） */
    private final Long userId;
    private final String authToken;

    public OrderTools(OrderClient orderClient, CourseClient courseClient,
                      ToolTrace trace, String requestId, Long userId, String authToken) {
        this.orderClient = orderClient;
        this.courseClient = courseClient;
        this.trace = trace;
        this.requestId = requestId;
        this.userId = userId;
        this.authToken = authToken;
    }

    @Tool(name = "prePlaceOrder", description = """
            为指定课程生成一个待支付订单，返回订单号、应付金额与课程清单。
            金额由交易服务从数据库计算，你不需要、也不能提供价格。
            courseIds 必须来自 searchCourses 的返回结果。
            生成后请把金额告诉用户并让他确认，由用户自己完成支付 —— 你不能替他付款。""")
    public String prePlaceOrder(
            @ToolParam(description = "要购买的课程 id 列表，必须来自 searchCourses 的结果") List<Long> courseIds) {
        if (!trace.tryAcquire()) {
            return "本轮工具调用次数已达上限，请直接基于已有信息回答，不要再调用工具。";
        }
        long start = System.currentTimeMillis();
        String args = "{\"courseIds\":" + courseIds + "}";
        if (courseIds == null || courseIds.isEmpty()) {
            trace.record("prePlaceOrder", args, "参数为空", System.currentTimeMillis() - start, false, null);
            return "没有指定要购买哪门课程。请先问清楚用户想买哪一门。";
        }
        try {
            // ★★ 第一步：校验存在性（在产生任何副作用之前）
            List<CourseSimpleDTO> courses = ToolAuthContext.runAs(userId, authToken,
                    () -> courseClient.queryCourseSimpleList(courseIds));
            List<Long> found = courses == null ? List.of() : courses.stream().map(CourseSimpleDTO::getId).toList();
            List<Long> missing = courseIds.stream().filter(id -> !found.contains(id)).toList();
            if (!missing.isEmpty()) {
                trace.record("prePlaceOrder", args, "课程不存在：" + missing,
                        System.currentTimeMillis() - start, false, null);
                log.warn("工具[prePlaceOrder] 收到不存在的课程 id：requestId={}，{}", requestId, missing);
                // 措辞刻意"对模型说话"：告诉它下一步该干什么，而不是只说失败
                return "没找到 id=" + missing + " 对应的课程，无法下单。"
                        + "请先用 searchCourses 搜索，并用真实存在的课程 id 重新调用；"
                        + "也不要向用户确认这个订单。";
            }

            // ★★ 第二步：金额由交易服务算（我们只给课程 id）
            // ★★ 这一步就是踩过坑的地方：工具跑在 Reactor 的 boundedElastic 线程上，
            //    不显式带身份的话 /orders/prePlace 会返回 4013「请先登录」
            PrePlaceOrderDTO order = ToolAuthContext.runAs(userId, authToken,
                    () -> orderClient.prePlace(courseIds));
            if (order == null || order.getOrderId() == null) {
                trace.record("prePlaceOrder", args, "预下单失败", System.currentTimeMillis() - start, false, null);
                return "下单失败，请让用户稍后再试。";
            }

            StringBuilder sb = new StringBuilder("已生成待支付订单：\n");
            sb.append("- 订单号：").append(order.getOrderId()).append('\n');
            if (order.getTotalAmount() != null) {
                sb.append("- 商品金额：").append(yuan(order.getTotalAmount())).append('\n');
            }
            if (order.getDiscountAmount() != null && order.getDiscountAmount() > 0) {
                sb.append("- 优惠金额：").append(yuan(order.getDiscountAmount())).append('\n');
            }
            sb.append("- **应付金额：").append(yuan(order.getRealAmount())).append("**（以这个数字为准）\n");
            if (order.getPayOutTime() != null) {
                sb.append("- 支付截止：").append(order.getPayOutTime()).append("，超时订单会自动取消\n");
            }
            sb.append("请把「应付金额」明确告诉用户，并让他确认后自行完成支付。你无法代替用户付款。\n");

            Map<String, Object> card = new LinkedHashMap<>();
            card.put("type", "ORDER_CONFIRM");
            card.put("title", "待支付订单");
            card.put("orderId", String.valueOf(order.getOrderId()));
            card.put("totalAmount", order.getTotalAmount());
            card.put("discountAmount", order.getDiscountAmount());
            card.put("realAmount", order.getRealAmount());
            card.put("payOutTime", order.getPayOutTime());
            List<Map<String, Object>> items = new ArrayList<>();
            if (order.getCourses() != null) {
                for (PrePlaceOrderDTO.Item it : order.getCourses()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("courseId", it.getCourseId());
                    m.put("courseName", it.getCourseName());
                    m.put("coverUrl", it.getCoverUrl());
                    m.put("price", it.getPrice());
                    m.put("validDuration", it.getValidDuration());
                    items.add(m);
                }
            }
            card.put("courses", items);
            trace.addCard(card);

            trace.record("prePlaceOrder", args, "订单 " + order.getOrderId() + " 应付 " + order.getRealAmount() + " 分",
                    System.currentTimeMillis() - start, false, null);
            log.info("工具调用[prePlaceOrder]：requestId={}，orderId={}，应付 {} 分",
                    requestId, order.getOrderId(), order.getRealAmount());
            return sb.toString();
        } catch (Exception e) {
            trace.record("prePlaceOrder", args, "下单失败", System.currentTimeMillis() - start, false, e.getMessage());
            log.warn("工具执行失败[prePlaceOrder]：requestId={}，{}", requestId, e.getMessage());
            return "下单暂时不可用，请告诉用户稍后再试。";
        }
    }

    private String yuan(Integer cents) {
        if (cents == null) {
            return "未知";
        }
        return String.format("%.2f 元", cents / 100.0);
    }
}
