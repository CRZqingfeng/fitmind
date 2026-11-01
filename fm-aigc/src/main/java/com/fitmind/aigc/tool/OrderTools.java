package com.fitmind.aigc.tool;

import com.fitmind.aigc.client.OrderClient;
import com.fitmind.aigc.client.dto.PlaceOrderRequest;
import com.fitmind.aigc.client.dto.PlaceOrderResultDTO;
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
import java.util.Objects;

/**
 * 下单工具 —— AI 只能"生成待支付订单"，不能完成支付。
 *
 * <p>★★ <b>四条写死的边界（每一条都对应一个真实风险）</b>：</p>
 * <ol>
 *   <li><b>金额不由模型决定。</b> 工具只传课程 id，价格全部由 fm-trade 从库里算。
 *       模型在对话里说"这门课 1 分钱"没有任何用 —— 它没有把这个数字送进交易的通道。
 *       （沿用 M4-1 的"金额不由前端给"原则，只是这次多了一个更不可信的前端：大模型。）</li>
 *   <li><b>先校验存在性，再调用有副作用的接口。</b> 顺序很重要：
 *       {@code place} 会在交易库里真的落一条待支付订单。如果先调它、失败了再兜错，
 *       就会为了一个不存在的课程产生一条脏订单。<b>把校验放在副作用之前。</b></li>
 *   <li><b>工具不完成支付。</b> 返回的是"待确认卡片"，用户看了金额之后自己点支付。
 *       一个会自己花钱的 AI 不是功能，是事故。</li>
 *   <li><b>★ 交付给用户的必须是"真的订单"。</b>（M6-8 #1）
 *       曾经这里只调了 {@code prePlace} —— 那是<b>只算价、不落库</b>的预览接口，
 *       却把返回值渲染成"待支付订单卡片"。用户点「去支付」→ 支付页查不到这个订单号
 *       → <b>AI 承诺的东西在系统里不存在</b>。
 *       现在对齐前端的 {@code prePlace → place} 两段式：<b>卡片上的订单号，库里必须真有这一行。</b></li>
 * </ol>
 *
 * <p>★ 顺带一个产品向的取舍：下单产生的待支付订单如果用户没支付，
 * 会被 M4-1 的超时取消任务自动关闭，所以<b>不会留下垃圾数据</b> ——
 * 这也是"敢让 AI 调它"的前提。</p>
 */
@Slf4j
public class OrderTools {

    /** ★ M6-8 #9：同 {@code CourseTools} —— 被限流拦下时也要留一条 trace，否则面板上的"超限拦截"标签永远不会亮 */
    private static final String BLOCKED_HINT =
            "本轮工具调用次数已达上限，请直接基于已有信息回答，不要再调用工具。";
    private static final String BLOCKED_SUMMARY = "超出本轮工具调用次数上限";
    private static final String BLOCKED_ERROR = "超出调用次数上限";

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
            生成的订单会真实保存，用户可以拿着订单号去支付。
            金额由交易服务从数据库计算，你不需要、也不能提供价格。
            courseIds 必须来自 searchCourses 的返回结果。
            生成后请把金额告诉用户并让他确认，由用户自己完成支付 —— 你不能替他付款。""")
    public String prePlaceOrder(
            @ToolParam(description = "要购买的课程 id 列表，必须来自 searchCourses 的结果") List<Long> courseIds) {
        String args = "{\"courseIds\":" + courseIds + "}";
        if (!trace.tryAcquire()) {
            trace.record("prePlaceOrder", args, BLOCKED_SUMMARY, 0, true, BLOCKED_ERROR);
            return BLOCKED_HINT;
        }
        long start = System.currentTimeMillis();
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

            // ★★ 第二步：预下单 —— 只算价 + 生成订单 id，★ 不落库
            // ★★ 这一步就是踩过坑的地方：工具跑在 Reactor 的 boundedElastic 线程上，
            //    不显式带身份的话 /orders/prePlace 会返回 4013「请先登录」
            PrePlaceOrderDTO preview = ToolAuthContext.runAs(userId, authToken,
                    () -> orderClient.prePlace(courseIds));
            if (preview == null) {
                trace.record("prePlaceOrder", args, "预下单没有返回结果", System.currentTimeMillis() - start, false, null);
                return "下单失败，请让用户稍后再试。";
            }
            // ★ 业务失败信封（HTTP 200 + R{code,msg}）：真实的"为什么买不了"在这里
            String previewError = bizError(preview.getCode(), preview.getMsg());
            if (previewError != null) {
                return rejected(previewError, args, start);
            }
            if (preview.getOrderId() == null) {
                trace.record("prePlaceOrder", args, "预下单失败", System.currentTimeMillis() - start, false, null);
                return "下单失败，请让用户稍后再试。";
            }

            // ★★ 第三步：真正落库（M6-8 #1）—— 用 prePlace 给的**同一个 orderId**
            //    沿用同一个 id = 天然的幂等：重试只会命中主键，不会造出第二笔单。
            PlaceOrderRequest request = new PlaceOrderRequest();
            request.setOrderId(preview.getOrderId());
            request.setCourseIds(courseIds);
            PlaceOrderResultDTO placed = ToolAuthContext.runAs(userId, authToken,
                    () -> orderClient.place(request));
            if (placed == null) {
                trace.record("prePlaceOrder", args, "下单没有返回结果，订单未创建",
                        System.currentTimeMillis() - start, false, null);
                log.warn("工具[prePlaceOrder] 落库未返回结果：requestId={}，orderId={}", requestId, preview.getOrderId());
                return "订单没有创建成功，请让用户稍后再试。**不要**向用户确认这个订单。";
            }
            String placeError = bizError(placed.getCode(), placed.getMsg());
            if (placeError != null) {
                return rejected(placeError, args, start);
            }
            if (placed.getOrderId() != null && !Objects.equals(placed.getOrderId(), preview.getOrderId())) {
                // 理论上不可能（幂等就是用这个 id 做的），真出现说明契约出问题了
                log.error("工具[prePlaceOrder] 订单号不一致：预下单 {}，落库 {} —— 请检查 fm-trade 的幂等实现",
                        preview.getOrderId(), placed.getOrderId());
            }

            // ★ 金额与截止时间一律以"落库那次"返回的为准（它才是订单里真正存下来的值）
            Integer realAmount = placed.getRealAmount() != null
                    ? placed.getRealAmount() : preview.getRealAmount();
            String payOutTime = placed.getPayOutTime() != null
                    ? placed.getPayOutTime() : preview.getPayOutTime();

            StringBuilder sb = new StringBuilder("已生成待支付订单（订单已保存，用户可直接去支付）：\n");
            sb.append("- 订单号：").append(placed.getOrderId() != null ? placed.getOrderId() : preview.getOrderId()).append('\n');
            if (preview.getTotalAmount() != null) {
                sb.append("- 商品金额：").append(yuan(preview.getTotalAmount())).append('\n');
            }
            if (preview.getDiscountAmount() != null && preview.getDiscountAmount() > 0) {
                sb.append("- 优惠金额：").append(yuan(preview.getDiscountAmount())).append('\n');
            }
            sb.append("- **应付金额：").append(yuan(realAmount)).append("**（以这个数字为准）\n");
            if (payOutTime != null) {
                sb.append("- 支付截止：").append(payOutTime).append("，超时订单会自动取消\n");
            }
            sb.append("请把「应付金额」明确告诉用户，并让他确认后自行完成支付。你无法代替用户付款。\n");

            Map<String, Object> card = new LinkedHashMap<>();
            card.put("type", "ORDER_CONFIRM");
            card.put("title", "待支付订单");
            card.put("orderId", String.valueOf(placed.getOrderId() != null ? placed.getOrderId() : preview.getOrderId()));
            card.put("totalAmount", preview.getTotalAmount());
            card.put("discountAmount", preview.getDiscountAmount());
            card.put("realAmount", realAmount);
            card.put("payOutTime", payOutTime);
            List<Map<String, Object>> items = new ArrayList<>();
            if (preview.getCourses() != null) {
                for (PrePlaceOrderDTO.Item it : preview.getCourses()) {
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

            trace.record("prePlaceOrder", args,
                    "订单 " + card.get("orderId") + " 已落库，应付 " + realAmount + " 分",
                    System.currentTimeMillis() - start, false, null);
            log.info("工具调用[prePlaceOrder]：requestId={}，orderId={}，应付 {} 分",
                    requestId, card.get("orderId"), realAmount);
            return sb.toString();
        } catch (Exception e) {
            trace.record("prePlaceOrder", args, "下单失败", System.currentTimeMillis() - start, false, e.getMessage());
            log.warn("工具执行失败[prePlaceOrder]：requestId={}，{}", requestId, e.getMessage());
            return "下单暂时不可用，请告诉用户稍后再试。";
        }
    }

    /**
     * ★★ 识别"业务失败信封"（M6-8 #5）。
     *
     * <p>被调方（fm-trade）把业务异常装进了<b>HTTP 200</b> 的 {@code R{code,msg}} 里，
     * Feign 拿到的既不是异常、也不是裸 VO。判据只有一条：
     * <b>{@code code == null} 表示裸 VO（正常成功）；{@code code != 200} 表示业务失败</b>。</p>
     *
     * <p>★ <b>为什么必须显式做这一步，而不是"加个 try-catch"</b>：
     * 这条路径上<b>根本没有异常</b>。不做这个判定，工具会把"你已经买过这门课"
     * 读成"服务没返回数据"，然后对用户说一句没营养的"下单失败，请稍后再试"——
     * <b>信息是在半路被丢掉的，不是被异常吃掉的。</b></p>
     *
     * @return 失败原因（人话）；返回 {@code null} 表示成功
     */
    private static String bizError(Integer code, String msg) {
        if (code == null) {
            return null;
        }
        if (code == 200) {
            // 防御：内部调用不该被包装成 R，真出现了说明包装规则变了
            log.warn("业务响应里出现了 code=200 的包装信封，请检查 WrapperResponseBodyAdvice 的网关判定");
            return null;
        }
        return (msg == null || msg.isBlank()) ? "交易服务没有给出失败原因（code=" + code + "）" : msg;
    }

    /**
     * 把业务失败原因回给模型 + 记进工具轨迹。
     *
     * <p>★ 两点都必要：</p>
     * <ul>
     *   <li><b>回给模型</b>：模型才能把"《X》你已经买过了"转述给用户，
     *       而不是继续编一个订单号；</li>
     *   <li><b>记进轨迹</b>：{@code trace} 会随 PARAM 事件下发并落库，
     *       出问题时能在「工具调用」里看到真实原因，而不是只有一句"失败"。</li>
     * </ul>
     *
     * <p>★ <b>"已购"单独给一句指令</b>：这是最高频的一种失败，而且它的正确回复
     * 不是"下单失败"而是"你已经有这门课了，去我的课程学"。
     * 与其指望模型每次都能从错误文案里推断出这个分支，
     * 不如<b>在错误已经明确的时候，把下一步动作一起告诉它</b>。</p>
     */
    private String rejected(String reason, String args, long start) {
        trace.record("prePlaceOrder", args, "下单被拒：" + reason,
                System.currentTimeMillis() - start, false, reason);
        log.info("工具[prePlaceOrder] 业务失败已透传：requestId={}，{}", requestId, reason);
        String hint = reason.contains("买过了") || reason.contains("已经买过")
                ? "这门课用户已经拥有 —— 请直接告诉他去「我的课程」就能开始学习，不要生成订单。"
                : "请把上面这个原因**原样**转述给用户，不要向用户确认订单，也不要自己编一个订单号。";
        return "下单没有成功：" + reason + "\n" + hint;
    }

    private String yuan(Integer cents) {
        if (cents == null) {
            return "未知";
        }
        return String.format("%.2f 元", cents / 100.0);
    }
}
