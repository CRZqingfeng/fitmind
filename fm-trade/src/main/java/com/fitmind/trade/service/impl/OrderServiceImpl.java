package com.fitmind.trade.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.client.pay.PayClient;
import com.fitmind.api.client.promotion.CouponClient;
import com.fitmind.api.dto.promotion.CouponDiscountDTO;
import com.fitmind.api.dto.course.CourseSimpleDTO;
import com.fitmind.api.dto.pay.ApplyPayDTO;
import com.fitmind.api.dto.pay.PayResultDTO;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.UserContext;
import com.fitmind.trade.config.TradeProperties;
import com.fitmind.trade.constants.OrderStatus;
import com.fitmind.trade.constants.TradeConstants;
import com.fitmind.trade.constants.TradeErrorInfo;
import com.fitmind.trade.domain.dto.PlaceOrderDTO;
import com.fitmind.trade.domain.po.TradeCart;
import com.fitmind.trade.domain.po.TradeOrder;
import com.fitmind.trade.domain.po.TradeOrderDetail;
import com.fitmind.trade.domain.query.OrderPageQuery;
import com.fitmind.trade.domain.vo.OrderDetailVO;
import com.fitmind.trade.domain.vo.OrderVO;
import com.fitmind.trade.domain.vo.PayUrlVO;
import com.fitmind.trade.domain.vo.PlaceOrderResultVO;
import com.fitmind.trade.domain.vo.PrePlaceOrderVO;
import com.fitmind.trade.mapper.TradeCartMapper;
import com.fitmind.trade.mapper.TradeOrderDetailMapper;
import com.fitmind.trade.mapper.TradeOrderMapper;
import com.fitmind.trade.service.CourseQueryHelper;
import com.fitmind.trade.service.IOrderService;
import com.fitmind.trade.util.RedisLockHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 订单服务实现。
 *
 * <p>本类里有两处是"看着多余、其实不能省"的：</p>
 * <ol>
 *   <li><b>订单 id 从请求里来</b>（前端预生成）：让"重复提交"变成一个主键冲突问题，
 *       而不是一个需要写去重逻辑的问题</li>
 *   <li><b>所有状态变更都走 {@link #changeStatus}</b>：条件更新让"并发"和"重复投递"
 *       这两件事同时被解决 —— 第二个调用者拿到的返回值是 false，于是什么都不做</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl extends ServiceImpl<TradeOrderMapper, TradeOrder> implements IOrderService {

    private final TradeProperties tradeProperties;
    private final CourseQueryHelper courseQueryHelper;
    private final TradeOrderDetailMapper orderDetailMapper;
    private final TradeCartMapper cartMapper;
    private final StringRedisTemplate redisTemplate;
    private final RedisLockHelper redisLockHelper;
    private final PayClient payClient;
    private final CouponClient couponClient;

    /**
     * ★ 编程式事务，而不是 {@code @Transactional} 注解。
     *
     * <p>理由只有一个，但很关键：<b>锁必须在事务之外</b>。</p>
     * <p>如果直接在 {@code placeOrder} 上打 {@code @Transactional}，
     * 那么方法的 {@code finally} 会在<b>事务提交之前</b>执行 ——
     * 锁已经放掉了，但数据还没提交。此时另一个请求拿到锁进来，
     * 读到的仍是旧状态。窗口只有毫秒级，但它是真实存在的。</p>
     * <p>用 {@code transactionTemplate.execute(...)} 把事务边界收在锁<b>内部</b>：
     * {@code execute} 返回时事务已经提交，{@code finally} 才去解锁。
     * 顺序就对了。</p>
     */
    private final TransactionTemplate transactionTemplate;

    // ==================== 预下单 ====================

    @Override
    public PrePlaceOrderVO prePlaceOrder(List<Long> courseIds) {
        Long userId = UserContext.getUser();
        if (CollectionUtils.isEmpty(courseIds)) {
            throw new BizIllegalException(TradeErrorInfo.Msg.CART_EMPTY);
        }
        // 1. 校验课程（存在 / 上架 / 付费课 / 未购买 / 无待支付单）
        List<CourseSimpleDTO> courses = validateCourses(userId, courseIds);

        // 2. 算钱。★ 价格全部来自课程服务当前值，绝不用前端传的、也不用购物车里的快照
        int totalAmount = sumPrice(courses);
        int discountAmount = 0;
        int realAmount = totalAmount - discountAmount;
        checkAmount(totalAmount, realAmount);

        // 3. 生成订单 id（前端带着它去提交），返回预览
        return new PrePlaceOrderVO()
                .setOrderId(IdUtil.getSnowflakeNextId())
                .setTotalAmount(totalAmount)
                .setDiscountAmount(discountAmount)
                .setRealAmount(realAmount)
                .setPayOutTime(LocalDateTime.now().plusMinutes(payOverMinutes()))
                .setCourses(courses.stream().map(this::toPreviewItem).collect(Collectors.toList()));
    }

    // ==================== 下单 ====================

    @Override
    public PlaceOrderResultVO placeOrder(PlaceOrderDTO dto) {
        Long userId = UserContext.getUser();
        String lockKey = TradeConstants.ORDER_LOCK_PREFIX + userId;
        String token = redisLockHelper.tryLock(
                lockKey, Duration.ofSeconds(TradeConstants.ORDER_LOCK_SECONDS));
        if (token == null) {
            // 挡住"手抖点两下"。真正的正确性由订单 id 主键 + 明细唯一性保证，不靠这把锁
            throw new BizIllegalException(TradeErrorInfo.Msg.ORDER_BUSY);
        }
        try {
            // 事务边界在锁<b>内部</b>：execute 返回时事务已提交，finally 才解锁
            return transactionTemplate.execute(status -> doPlaceOrder(dto, userId));
        } finally {
            redisLockHelper.unlock(lockKey, token);
        }
    }

    /**
     * 下单主流程。
     *
     * <p>本方法跑在 {@link #placeOrder} 用 {@code transactionTemplate} 开的事务里
     * —— 所以「写主单 + 写明细 + 清购物车」这三步的数据库操作是原子的。</p>
     *
     * <p>⚠️ <b>但投递延迟消息（ZADD）不在事务里</b>：Redis 不参与数据库事务。
     * 如果事务最终回滚，ZSet 里会留下一个指向不存在订单的 member ——
     * 后果是定时任务白跑一次（条件更新会 update 到 0 行），不会出错。
     * 这个取舍是刻意的：宁可留一个无害的垃圾消息，也不要为了"干净"把
     * Redis 操作塞进事务里（那反而会引入分布式事务问题）。</p>
     */
    private PlaceOrderResultVO doPlaceOrder(PlaceOrderDTO dto, Long userId) {
        // 0. ★ 幂等：订单 id 已存在说明是重复提交（或非法传入了别人的 id）
        TradeOrder exist = getById(dto.getOrderId());
        if (exist != null) {
            if (!Objects.equals(exist.getUserId(), userId)) {
                log.warn("下单 id 冲突：orderId={} 属于用户 {}，但请求来自 {}", dto.getOrderId(), exist.getUserId(), userId);
                throw new BizIllegalException(TradeErrorInfo.Msg.ORDER_ID_EXISTS);
            }
            log.info("重复提交下单请求，返回已有订单：orderId={}", dto.getOrderId());
            return toResult(exist, false);
        }

        // 1. 校验课程
        List<CourseSimpleDTO> courses = validateCourses(userId, dto.getCourseIds());
        List<Long> courseIds = courses.stream().map(CourseSimpleDTO::getId).collect(Collectors.toList());

        // 2. 算钱
        int totalAmount = sumPrice(courses);
        // ★ 向促销服务求最优用券方案（金额由服务端算，不信前端）
        CouponDiscountDTO discount = queryDiscount(dto, courses, userId);
        int discountAmount = discount == null ? 0 : discount.getDiscountAmount();
        int realAmount = totalAmount - discountAmount;
        checkAmount(totalAmount, realAmount);

        // 3. 写主单
        LocalDateTime now = LocalDateTime.now();
        TradeOrder order = new TradeOrder()
                .setId(dto.getOrderId())
                .setUserId(userId)
                .setStatus(OrderStatus.NO_PAY.getValue())
                .setTotalAmount(totalAmount)
                .setDiscountAmount(discountAmount)
                .setRealAmount(realAmount)
                .setCouponIds(discount == null ? null : discount.getIds())
                .setCreateTime(now);
        try {
            save(order);
        } catch (DuplicateKeyException e) {
            // 并发重复提交：另一个请求刚插进去。回过头读出来返回即可（不下单两次）
            TradeOrder again = getById(dto.getOrderId());
            if (again != null && Objects.equals(again.getUserId(), userId)) {
                log.info("并发重复提交，返回已创建的订单：orderId={}", dto.getOrderId());
                return toResult(again, false);
            }
            throw new BizIllegalException(TradeErrorInfo.Msg.ORDER_ID_EXISTS);
        }

        // 4. 写明细（★ 全部是下单时刻的快照）
        for (CourseSimpleDTO course : courses) {
            // 这门课分摊到的优惠（促销服务算好，按各课金额比例摊）
            int courseDiscount = 0;
            if (discount != null && discount.getDiscountDetail() != null) {
                courseDiscount = discount.getDiscountDetail()
                        .getOrDefault(course.getId(), 0);
            }
            orderDetailMapper.insert(new TradeOrderDetail()
                    .setId(IdUtil.getSnowflakeNextId())
                    .setOrderId(order.getId())
                    .setUserId(userId)
                    .setCourseId(course.getId())
                    .setCourseName(course.getName())
                    .setCoverUrl(course.getCoverUrl())
                    .setPrice(priceOf(course))
                    .setDiscountAmount(courseDiscount)
                    .setRealPayAmount(priceOf(course) - courseDiscount)
                    .setValidDuration(course.getValidDuration())
                    .setStatus(TradeConstants.DETAIL_NO_PAY));
        }

        // 5. 投递延迟消息：到点没支付就自动关单
        LocalDateTime payOutTime = now.plusMinutes(payOverMinutes());
        redisTemplate.opsForZSet().add(TradeConstants.ORDER_DELAY_KEY,
                order.getId().toString(), toEpochMilli(payOutTime));

        // 6. 把下单的课程从购物车里移除（结算过的条目不该留在购物车里）
        cartMapper.delete(new LambdaQueryWrapper<TradeCart>()
                .eq(TradeCart::getUserId, userId)
                .in(TradeCart::getCourseId, courseIds));

        log.info("下单成功：orderId={}, userId={}, 课程 {} 门, 实付 {} 分, 支付截止 {}",
                order.getId(), userId, courses.size(), realAmount, payOutTime);
        return toResult(order, true).setPayOutTime(payOutTime);
    }

    // ==================== 查询 ====================

    @Override
    public PageDTO<OrderVO> queryMyOrders(OrderPageQuery query) {
        Long userId = UserContext.getUser();
        Page<TradeOrder> page = page(query.toMpPageDefaultSortByCreateTimeDesc(),
                new LambdaQueryWrapper<TradeOrder>()
                        .eq(TradeOrder::getUserId, userId)
                        .eq(query.getStatus() != null, TradeOrder::getStatus, query.getStatus()));
        if (CollectionUtils.isEmpty(page.getRecords())) {
            return PageDTO.empty(page);
        }
        // 批量查明细，避免每单查一次（N+1）
        List<Long> orderIds = page.getRecords().stream().map(TradeOrder::getId).collect(Collectors.toList());
        Map<Long, List<TradeOrderDetail>> detailMap = orderDetailMapper.selectList(
                        new LambdaQueryWrapper<TradeOrderDetail>()
                                .in(TradeOrderDetail::getOrderId, orderIds)
                                .orderByAsc(TradeOrderDetail::getId))
                .stream().collect(Collectors.groupingBy(TradeOrderDetail::getOrderId));
        return PageDTO.of(page, o -> toVO(o, detailMap.get(o.getId())));
    }

    @Override
    public OrderVO queryOrderDetail(Long orderId) {
        TradeOrder order = requireMyOrder(orderId);
        return toVO(order, orderDetailMapper.getByOrderId(orderId));
    }

    // ==================== 状态变更 ====================

    @Override
    public boolean changeStatus(TradeOrder update, OrderStatus expect) {
        // 第一道：状态机（防写错代码）。非法流转直接抛，不静默
        OrderStatus target = OrderStatus.of(update.getStatus());
        OrderStatus.checkTransfer(expect, target);
        // 第二道：条件更新（防并发 + 天然幂等）
        return baseMapper.changeStatus(update, expect.getValue()) > 0;
    }

    @Override
    public PayUrlVO applyPay(Long orderId, String payChannelCode) {
        TradeOrder order = requireMyOrder(orderId);
        if (!Objects.equals(order.getStatus(), OrderStatus.NO_PAY.getValue())) {
            throw new BizIllegalException(TradeErrorInfo.Msg.ORDER_STATUS_CANNOT_PAY);
        }
        // ★ 金额来自服务端订单，不信任何传入值
        ApplyPayDTO dto = new ApplyPayDTO();
        dto.setBizOrderNo(String.valueOf(orderId));
        dto.setBizUserId(order.getUserId());
        dto.setAmount(order.getRealAmount());
        dto.setPayChannelCode(payChannelCode);

        PayResultDTO result;
        try {
            result = payClient.applyPay(dto);
        } catch (Exception e) {
            log.error("申请支付失败：orderId={}", orderId, e);
            throw new BizIllegalException(TradeErrorInfo.Msg.PAY_SERVICE_UNAVAILABLE);
        }
        // ⚠️ 降级返回 null（不是空对象）—— 必须能区分"支付服务挂了"和"申请成功"
        if (result == null || result.getPayOrderNo() == null) {
            throw new BizIllegalException(TradeErrorInfo.Msg.PAY_SERVICE_UNAVAILABLE);
        }
        log.info("发起支付：orderId={}, payOrderNo={}, 金额={}分", orderId,
                result.getPayOrderNo(), order.getRealAmount());
        return new PayUrlVO()
                .setOrderId(orderId)
                .setPayOrderNo(result.getPayOrderNo())
                .setQrCodeUrl(result.getQrCodeUrl())
                .setAmount(order.getRealAmount())
                .setExpireTime(result.getExpireTime())
                .setReused(result.getReused());
    }

    @Override
    public void cancelOrder(Long orderId) {
        TradeOrder order = requireMyOrder(orderId);
        boolean ok = changeStatus(new TradeOrder()
                .setId(orderId)
                .setStatus(OrderStatus.CLOSED.getValue())
                .setCloseTime(LocalDateTime.now())
                .setMessage("用户取消"), OrderStatus.NO_PAY);
        if (!ok) {
            // 走到这里说明状态已经不是待支付了：可能刚被支付、也可能刚被超时任务关掉
            throw new BizIllegalException(TradeErrorInfo.Msg.ORDER_CANNOT_CANCEL);
        }
        // ★ 同步明细状态：不同步的话，"待支付明细"会一直挂着，
        //   用户之后再买同一门课会被"你有一笔待支付的订单"挡住（验收抓到的 bug）
        closeDetails(orderId);
        // 从延迟队列里摘掉（不摘也不会出错 —— 条件更新会挡住 —— 但会白跑一次任务）
        redisTemplate.opsForZSet().remove(TradeConstants.ORDER_DELAY_KEY, orderId.toString());
        log.info("用户取消订单：orderId={}, userId={}", orderId, order.getUserId());
    }

    @Override
    public boolean closeTimeoutOrder(Long orderId) {
        boolean ok = changeStatus(new TradeOrder()
                .setId(orderId)
                .setStatus(OrderStatus.CLOSED.getValue())
                .setCloseTime(LocalDateTime.now())
                .setMessage("超时未支付"), OrderStatus.NO_PAY);
        if (ok) {
            // ★ 同 cancelOrder：明细必须跟着关，否则这门课会永远"买不了"
            closeDetails(orderId);
            log.info("订单超时关闭：orderId={}", orderId);
        } else {
            // 常见且正常：订单在到期前被支付了，或者被用户主动取消了
            log.info("订单无需超时关闭（状态已变更）：orderId={}", orderId);
        }
        return ok;
    }

    // ==================== 内部方法 ====================

    /**
     * ★ 向促销服务求用券方案。
     *
     * <p><b>金额一律由促销服务算</b> —— 我们只把"买了哪几门课、各多少钱"传过去，
     * 优惠多少由它算。这样"券的规则"只有一个数据源，
     * 交易侧不需要知道满减/折扣/每满减的区别。</p>
     *
     * <p><b>★ 为什么是"按用户选的券算"而不是"直接取最优方案"</b>：
     * 前端会把叠加推荐的最优方案预勾选给用户，但用户完全可以自己增减券。
     * 所以下单时要按"<b>用户最终选中的那几张</b>"重算 ——
     * 而不是要求用户的选择必须等于系统推荐（那样用户一改选就下不了单）。</p>
     *
     * <p><b>降级策略</b>：促销服务不可用时<b>中断下单</b>，而不是按原价继续。
     * 理由：用户手上有券却按原价付了钱，这个错误比"下单失败"严重得多 ——
     * 前者用户会投诉，后者刷新重试即可。</p>
     */
    private CouponDiscountDTO queryDiscount(PlaceOrderDTO dto,
                                            List<CourseSimpleDTO> courses, Long userId) {
        if (CollectionUtils.isEmpty(dto.getUserCouponIds())) {
            // 用户没用券，不必去问促销服务
            return null;
        }
        List<Long> courseIds = courses.stream().map(CourseSimpleDTO::getId).collect(Collectors.toList());
        List<Integer> prices = courses.stream().map(this::priceOf).collect(Collectors.toList());
        CouponDiscountDTO detail;
        try {
            // 促销服务不可用时，Fallback 会抛业务异常（与本方法无关的异常才被下面的 catch 兜住）
            detail = couponClient.queryDetail(userId, courseIds, prices, dto.getUserCouponIds());
        } catch (BizIllegalException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用促销服务失败：userId={}", userId, e);
            throw new BizIllegalException(TradeErrorInfo.Msg.PROMOTION_SERVICE_UNAVAILABLE);
        }
        // 返回 null = 这些券在这笔订单上用不了（不满门槛 / 不在限定范围 / 已过期）
        if (detail == null) {
            log.warn("用户选择的券不可用：userId={}, userCouponIds={}", userId, dto.getUserCouponIds());
            throw new BizIllegalException(TradeErrorInfo.Msg.COUPON_UNAVAILABLE);
        }
        return detail;
    }

    /**
     * 下单/预下单共用的课程校验。
     *
     * <p>顺序是按"用户最容易犯的错"排的：先看课程还在不在、再看是不是免费课
     * （免费课不该走下单，直接报名）、再看是不是买过了、最后看有没有未处理的旧单。</p>
     */
    private List<CourseSimpleDTO> validateCourses(Long userId, List<Long> courseIds) {
        // 1. 存在 + 已上架（课程服务不可用会在这里抛"稍后重试"，不做降级）
        List<CourseSimpleDTO> courses = courseQueryHelper.queryOnShelfCourses(courseIds);

        // 2. 免费课不走下单：直接报名即可，否则会造出一堆 0 元订单
        for (CourseSimpleDTO course : courses) {
            if (CourseQueryHelper.isFree(course)) {
                throw new BizIllegalException(
                        String.format(TradeErrorInfo.Msg.ORDER_COURSE_FREE, course.getName()));
            }
        }

        List<Long> ids = courses.stream().map(CourseSimpleDTO::getId).collect(Collectors.toList());

        // 3. 不能重复购买
        List<Long> bought = orderDetailMapper.queryBoughtCourseIds(
                userId, ids, TradeConstants.DETAIL_PAYED);
        if (!bought.isEmpty()) {
            throw new BizIllegalException(String.format(TradeErrorInfo.Msg.ORDER_COURSE_ALREADY_BOUGHT,
                    nameOf(courses, bought.get(0))));
        }

        // 4. 同一门课不能同时有未支付的订单（否则用户会重复下单、重复付款）
        List<Long> pending = orderDetailMapper.queryPendingCourseIds(
                userId, ids, TradeConstants.DETAIL_NO_PAY);
        if (!pending.isEmpty()) {
            throw new BizIllegalException(String.format(TradeErrorInfo.Msg.ORDER_COURSE_HAS_PENDING,
                    nameOf(courses, pending.get(0))));
        }
        return courses;
    }

    /**
     * 关闭订单时把它的"待支付明細"一起置为已关闭。
     *
     * <p>只更新仍是<b>待支付</b>的明细：万一将来有部分明细已支付（部分退款场景），
     * 不能把它们一起关掉。</p>
     */
    private void closeDetails(Long orderId) {
        orderDetailMapper.update(null, new LambdaUpdateWrapper<TradeOrderDetail>()
                .set(TradeOrderDetail::getStatus, TradeConstants.DETAIL_CLOSED)
                .eq(TradeOrderDetail::getOrderId, orderId)
                .eq(TradeOrderDetail::getStatus, TradeConstants.DETAIL_NO_PAY));
    }

    private String nameOf(List<CourseSimpleDTO> courses, Long courseId) {
        return courses.stream()
                .filter(c -> Objects.equals(c.getId(), courseId))
                .map(CourseSimpleDTO::getName)
                .findFirst()
                .orElse(String.valueOf(courseId));
    }

    private int sumPrice(List<CourseSimpleDTO> courses) {
        return courses.stream().mapToInt(this::priceOf).sum();
    }

    private int priceOf(CourseSimpleDTO course) {
        return course.getPrice() == null ? 0 : course.getPrice();
    }

    /** 金额必须为正：0 元订单说明数据有问题（免费课已经在前面被拦掉了） */
    private void checkAmount(int totalAmount, int realAmount) {
        if (totalAmount <= 0 || realAmount < 0) {
            log.error("订单金额异常：totalAmount={}, realAmount={}", totalAmount, realAmount);
            throw new BizIllegalException(TradeErrorInfo.Msg.ORDER_AMOUNT_ILLEGAL);
        }
    }

    private TradeOrder requireMyOrder(Long orderId) {
        TradeOrder order = getById(orderId);
        if (order == null) {
            throw new BizIllegalException(TradeErrorInfo.Msg.ORDER_NOT_EXISTS);
        }
        if (!Objects.equals(order.getUserId(), UserContext.getUser())) {
            throw new BizIllegalException(TradeErrorInfo.Msg.ORDER_NOT_MINE);
        }
        return order;
    }

    private int payOverMinutes() {
        return tradeProperties.getOrder().getPayOverMinutes();
    }

    private LocalDateTime payOutTimeOf(TradeOrder order) {
        return order.getCreateTime() == null ? null : order.getCreateTime().plusMinutes(payOverMinutes());
    }

    private static long toEpochMilli(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private PlaceOrderResultVO toResult(TradeOrder order, boolean created) {
        return new PlaceOrderResultVO()
                .setOrderId(order.getId())
                .setRealAmount(order.getRealAmount())
                .setPayOutTime(payOutTimeOf(order))
                .setCreated(created);
    }

    private PrePlaceOrderVO.Item toPreviewItem(CourseSimpleDTO course) {
        return new PrePlaceOrderVO.Item()
                .setCourseId(course.getId())
                .setCourseName(course.getName())
                .setCoverUrl(course.getCoverUrl())
                .setPrice(priceOf(course))
                .setValidDuration(course.getValidDuration());
    }

    private OrderVO toVO(TradeOrder order, List<TradeOrderDetail> details) {
        OrderStatus status = OrderStatus.of(order.getStatus());
        boolean noPay = status == OrderStatus.NO_PAY;
        return new OrderVO()
                .setId(order.getId())
                .setStatus(order.getStatus())
                .setStatusDesc(OrderStatus.desc(status))
                .setTotalAmount(order.getTotalAmount())
                .setDiscountAmount(order.getDiscountAmount())
                .setRealAmount(order.getRealAmount())
                .setMessage(order.getMessage())
                .setCreateTime(order.getCreateTime())
                .setPayTime(order.getPayTime())
                .setCloseTime(order.getCloseTime())
                .setPayOutTime(noPay ? payOutTimeOf(order) : null)
                // 按钮状态由服务端给：前端不要拿 status 数字自己判断
                .setCanPay(noPay)
                .setCanCancel(noPay)
                .setDetails(details == null ? new ArrayList<>()
                        : details.stream().map(this::toDetailVO).collect(Collectors.toList()));
    }

    /**
     * ★ 按明细聚合父单状态（M4-4 退款流程专用）。
     *
     * <p>聚合规则（只在这一处实现，避免"四个入口各算一遍"）：</p>
     * <pre>
     *   全部明细已退款   → REFUND_SUCCESS（终态）
     *   仍有明细退款中   → REFUNDED（退款中）
     *   以上都不是       → PAYED（退款被拒/失败/撤销，回到已支付）
     * </pre>
     *
     * <p>★ 这里有个容易想错的地方：<b>不能"一有退款就把整单标成已退款"</b>。
     * 一笔订单买了 3 门课，退掉第 1 门时，另外 2 门的权益还在 ——
     * 父单必须仍是"已支付"，否则订单层面看起来这笔交易整个撤了。</p>
     *
     * <p>状态变更走 {@link #changeStatus}（状态机校验 + 条件更新）。
     * 目标状态与当前状态相同就直接返回 —— 退款流程会频繁调用本方法
     * （申请时、审批时、回调时各一次），不做这个短路会产生大量无意义的 UPDATE。</p>
     */
    @Override
    public void syncRefundStatus(Long orderId) {
        TradeOrder order = getById(orderId);
        if (order == null) {
            return;
        }
        List<TradeOrderDetail> details = orderDetailMapper.getByOrderId(orderId);
        if (details.isEmpty()) {
            return;
        }
        boolean allRefunded = details.stream()
                .allMatch(d -> Objects.equals(d.getStatus(), TradeConstants.DETAIL_REFUNDED));
        boolean anyRefunding = details.stream()
                .anyMatch(d -> Objects.equals(d.getStatus(), TradeConstants.DETAIL_REFUNDING));
        OrderStatus target = allRefunded ? OrderStatus.REFUND_SUCCESS
                : (anyRefunding ? OrderStatus.REFUNDED : OrderStatus.PAYED);
        OrderStatus current = OrderStatus.of(order.getStatus());
        if (current == target) {
            return;
        }
        if (!OrderStatus.canTransfer(current, target)) {
            // 状态机不允许（比如订单已关闭）—— 记日志而不是抛异常：
            // 这里是被退款流程顺带调用的，不能因为一笔历史脏数据而让整个退款失败
            log.warn("父单状态无法按明细聚合出来：orderId={}, 当前={}, 期望={}",
                    orderId, OrderStatus.desc(current), OrderStatus.desc(target));
            return;
        }
        boolean ok = changeStatus(new TradeOrder().setId(orderId).setStatus(target.getValue()), current);
        log.info("父单退款状态聚合：orderId={}, {} → {}, 明细 {} 门（已退 {} 门）",
                orderId, OrderStatus.desc(current), OrderStatus.desc(target),
                details.size(),
                details.stream().filter(d -> Objects.equals(d.getStatus(), TradeConstants.DETAIL_REFUNDED)).count());
        if (!ok) {
            // 条件更新失败 = 并发下别人先改了，属于正常（下次聚合会对齐）
            log.info("父单状态聚合被并发抢先，本次未生效：orderId={}", orderId);
        }
    }

    private OrderDetailVO toDetailVO(TradeOrderDetail d) {
        return new OrderDetailVO()
                .setId(d.getId())
                .setCourseId(d.getCourseId())
                .setCourseName(d.getCourseName())
                .setCoverUrl(d.getCoverUrl())
                .setPrice(d.getPrice())
                .setDiscountAmount(d.getDiscountAmount())
                .setRealPayAmount(d.getRealPayAmount())
                .setValidDuration(d.getValidDuration())
                .setCourseExpireTime(d.getCourseExpireTime())
                .setStatus(d.getStatus());
    }
}
