package com.fitmind.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.client.pay.PayClient;
import com.fitmind.api.dto.pay.RefundApplyDTO;
import com.fitmind.api.dto.pay.RefundResultDTO;
import com.fitmind.common.constants.MqConstants;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.mq.message.OrderRefundMessage;
import com.fitmind.common.mq.message.RefundResultMessage;
import com.fitmind.common.utils.RabbitMqHelper;
import com.fitmind.common.utils.UserContext;
import com.fitmind.trade.constants.OrderStatus;
import com.fitmind.trade.constants.RefundStatus;
import com.fitmind.trade.constants.TradeConstants;
import com.fitmind.trade.constants.TradeErrorInfo;
import com.fitmind.trade.domain.dto.ApproveFormDTO;
import com.fitmind.trade.domain.dto.RefundCancelDTO;
import com.fitmind.trade.domain.dto.RefundFormDTO;
import com.fitmind.trade.domain.po.RefundApply;
import com.fitmind.trade.domain.po.TradeOrder;
import com.fitmind.trade.domain.po.TradeOrderDetail;
import com.fitmind.trade.domain.query.RefundApplyPageQuery;
import com.fitmind.trade.domain.vo.RefundApplyPageVO;
import com.fitmind.trade.domain.vo.RefundApplyVO;
import com.fitmind.trade.mapper.RefundApplyMapper;
import com.fitmind.trade.mapper.TradeOrderDetailMapper;
import com.fitmind.trade.mapper.TradeOrderMapper;
import com.fitmind.trade.service.IOrderService;
import com.fitmind.trade.service.IRefundApplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 退款申请服务实现。
 *
 * <p><b>★ 这个类里最值得看的三处</b>：</p>
 * <ol>
 *   <li><b>"能不能退"按明细判断，不按父单判断</b> —— 一笔订单买 3 门课，
 *       退 1 门时父单已经变成"退款中"，但另外 2 门课仍然可以退。
 *       常见实现按父单状态判断，于是退了一门之后另外两门就退不了了。</li>
 *   <li><b>并发靠两层</b>：DB 唯一索引（{@code uk_active_detail}）兜住重复申请，
 *       状态条件更新兜住重复审批。分布式锁只是"让重复请求快速返回"的优化，不是保障。</li>
 *   <li><b>外部调用在事务之外</b> —— "同意"之后要调支付服务，那是网络 IO，
 *       放进事务里会让 DB 连接被长时间占用；而且服务不可用时<b>不能标失败</b>
 *       （钱还没发出去，标失败会让用户以为退款被拒）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundApplyServiceImpl extends ServiceImpl<RefundApplyMapper, RefundApply>
        implements IRefundApplyService {

    private final TradeOrderDetailMapper detailMapper;
    private final TradeOrderMapper orderMapper;
    private final IOrderService orderService;
    private final PayClient payClient;
    private final RabbitMqHelper rabbitMqHelper;
    private final TransactionTemplate transactionTemplate;

    // ==================== ① 申请 ====================

    @Override
    public Long applyRefund(RefundFormDTO dto) {
        Long userId = UserContext.getUser();
        // ★ 用户手里只有 courseId（"我要退这门课"），明细 id 是服务端内部标识 ——
        //   这个转换本来就该在服务端做。见 RefundFormDTO 的注释。
        TradeOrderDetail detail = findRefundableDetailByCourse(userId, dto.getCourseId());
        // 1. 有进行中的申请 → 直接拒绝（快速失败；并发场景由唯一索引兜底）
        List<RefundApply> history = baseMapper.listByDetailId(detail.getId());
        for (RefundApply h : history) {
            if (RefundStatus.inProgress(h.getStatus())) {
                throw new BizIllegalException(TradeErrorInfo.Msg.REFUND_IN_PROGRESS);
            }
        }
        // 2. 申请次数限制（业务规则；常见实现也是 2 次）
        if (history.size() >= TradeConstants.STUDENT_MAX_REFUND_TIMES) {
            throw new BizIllegalException(String.format(
                    TradeErrorInfo.Msg.REFUND_TOO_MANY_TIMES, TradeConstants.STUDENT_MAX_REFUND_TIMES));
        }
        // 3. 可退金额 = 实付 - 已成功退款的（★ 服务端自己算，不信前端）
        int amount = remainRefundableAmount(detail);
        return create(detail, amount, dto.getRefundReason(), dto.getQuestionDesc(),
                RefundStatus.UN_APPROVE, userId);
    }

    @Override
    public Long adminRefund(Long orderDetailId, String reason) {
        Long operator = UserContext.getUser();
        // 管理员代退：明细归属不校验为"本人"（管理员本来就不是订单用户），
        // 但后面的 setUserId 仍然用 detail.getUserId() —— 钱退给订单主人，不是操作人
        TradeOrderDetail detail = requireRefundableDetail(orderDetailId, null);
        int amount = remainRefundableAmount(detail);
        log.info("管理员直接退款：operator={}, detailId={}, amount={}分", operator, orderDetailId, amount);
        // 初始状态就是"同意" —— 跳过审批，但后续链路与学员申请完全一致
        return create(detail, amount, reason, "管理员直接退款", RefundStatus.AGREE, operator);
    }

    /**
     * 按 (用户, 课程) 找"最近一笔可退的明细"。
     *
     * <p>为什么用户端用 courseId 而不是明细 id：用户看到的是"我要退这门课"，
     * 他手里根本没有明细 id（订单详情接口返回的明细里也不含 id）。
     * 让用户传一个拿不到的值，接口设计上就是走不通的。</p>
     *
     * <p>"最近一笔"很重要：用户退过款又重买过同一门课，会存在多条明细 ——
     * 应该退的是<b>最新那笔</b>，而不是历史上任意一条。</p>
     */
    private TradeOrderDetail findRefundableDetailByCourse(Long userId, Long courseId) {
        List<TradeOrderDetail> all = detailMapper.selectList(new LambdaQueryWrapper<TradeOrderDetail>()
                .eq(TradeOrderDetail::getUserId, userId)
                .eq(TradeOrderDetail::getCourseId, courseId)
                .orderByDesc(TradeOrderDetail::getId));
        if (all.isEmpty()) {
            throw new BizIllegalException(TradeErrorInfo.Msg.REFUND_COURSE_NOT_BOUGHT);
        }
        return all.stream()
                .filter(d -> Objects.equals(d.getStatus(), TradeConstants.DETAIL_PAYED))
                .findFirst()
                .orElseThrow(() -> new BizIllegalException(TradeErrorInfo.Msg.REFUND_ORDER_STATUS_ILLEGAL));
    }

    /**
     * 校验"这个明细能不能退"。
     *
     * <p><b>★ 注意校验的是明细状态，不是订单状态</b>。
     * 一笔订单 3 门课，用户退掉第 1 门后父单变成"退款中" ——
     * 如果按父单判断，另外 2 门就再也退不了了（常见实现的做法）。
     * 按明细判断则天然正确：还有哪门课是"已支付"，哪门就能退。</p>
     *
     * @param ownerId 期望的明细归属人；传 null 表示不校验归属（管理员通道）
     */
    private TradeOrderDetail requireRefundableDetail(Long detailId, Long ownerId) {
        TradeOrderDetail detail = detailMapper.selectById(detailId);
        if (detail == null) {
            throw new BizIllegalException(TradeErrorInfo.Msg.REFUND_ORDER_DETAIL_NOT_EXISTS);
        }
        if (ownerId != null && !Objects.equals(detail.getUserId(), ownerId)) {
            throw new BizIllegalException(TradeErrorInfo.Msg.REFUND_NOT_MINE);
        }
        if (detail.getRealPayAmount() == null || detail.getRealPayAmount() <= 0) {
            // 免费课直接报名即可，没有钱可退
            throw new BizIllegalException(TradeErrorInfo.Msg.REFUND_FREE_COURSE);
        }
        if (!Objects.equals(detail.getStatus(), TradeConstants.DETAIL_PAYED)) {
            // 待支付 / 已关闭 / 退款中 / 已退款 —— 都不能再发起退款
            throw new BizIllegalException(TradeErrorInfo.Msg.REFUND_ORDER_STATUS_ILLEGAL);
        }
        return detail;
    }

    /** 可退金额 = 明细实付 - 该明细已成功退款的累计 */
    private int remainRefundableAmount(TradeOrderDetail detail) {
        Integer refunded = baseMapper.sumSuccessAmountByDetailId(
                detail.getId(), RefundStatus.SUCCESS.getValue());
        int remain = detail.getRealPayAmount() - (refunded == null ? 0 : refunded);
        if (remain <= 0) {
            // 已经全额退过（比如失败后重试成功过一次），不能再退
            throw new BizIllegalException(TradeErrorInfo.Msg.REFUND_AMOUNT_ILLEGAL);
        }
        return remain;
    }

    /**
     * 落库 + 推进状态（学员申请与管理员退回共用）。
     *
     * @param initStatus 学员申请 = 待审批；管理员直接退 = 同意
     */
    private Long create(TradeOrderDetail detail, int amount, String reason, String desc,
                        RefundStatus initStatus, Long creater) {
        RefundApply apply = new RefundApply()
                .setOrderDetailId(detail.getId())
                .setOrderId(detail.getOrderId())
                // ★ 钱退给订单主人，不是操作人（管理员代退时两者不同）
                .setUserId(detail.getUserId())
                .setRefundAmount(amount)
                .setRefundReason(reason)
                .setQuestionDesc(desc)
                .setStatus(initStatus.getValue())
                .setMessage(initStatus.getDesc())
                .setCreater(creater);
        try {
            save(apply);
        } catch (DuplicateKeyException e) {
            // ★ 并发重复申请：两人同时通过上面的检查，这里由 uk_active_detail 挡住第二个。
            //   这不是系统异常，是"已经有了"，转成业务提示
            log.info("并发重复退款申请被唯一索引挡住：detailId={}", detail.getId());
            throw new BizIllegalException(TradeErrorInfo.Msg.REFUND_IN_PROGRESS);
        }
        // 明细 → 退款中（条件更新：必须是"已支付"，防并发）
        int n = detailMapper.changeStatus(detail.getId(),
                TradeConstants.DETAIL_PAYED, TradeConstants.DETAIL_REFUNDING);
        if (n == 0) {
            // 极端并发：明细状态被别的流程改了。申请已落库，交由后续审批/补偿收拾
            log.warn("明细状态在申请过程中被改动：detailId={}, 当前申请 id={}", detail.getId(), apply.getId());
        }
        // 父单状态按明细聚合（这里会算成"退款中"）
        orderService.syncRefundStatus(detail.getOrderId());
        // 管理员直接退：跳过审批，立刻把退款请求发出去
        if (initStatus == RefundStatus.AGREE) {
            sendRefundRequest(apply.getId());
        }
        return apply.getId();
    }

    // ==================== ② 审批 / 撤销 ====================

    @Override
    public void approve(ApproveFormDTO dto) {
        RefundApply apply = getById(dto.getId());
        if (apply == null) {
            throw new BizIllegalException(TradeErrorInfo.Msg.REFUND_APPLY_NOT_EXISTS);
        }
        RefundStatus target = dto.getApproveType() == 1 ? RefundStatus.AGREE : RefundStatus.REJECT;
        // 状态机校验：只有"待审批"能变成同意/拒绝
        RefundStatus.checkTransfer(RefundStatus.of(apply.getStatus()), target);
        // ① 事务内抢审批权。★ 条件更新（WHERE status = 待审批）保证并发下只有一个能成功 ——
        //    常见实现先 getById 判状态、再 updateById，两个管理员同时点"同意"会都通过
        Integer n = transactionTemplate.execute(st -> baseMapper.approve(
                apply.getId(), RefundStatus.UN_APPROVE.getValue(), target.getValue(),
                target.getDesc(), UserContext.getUser(), dto.getApproveOpinion()));
        if (n == null || n == 0) {
            throw new BizIllegalException(TradeErrorInfo.Msg.REFUND_APPLY_APPROVED);
        }
        log.info("退款申请审批完成：applyId={}, 结果={}, 审批人={}",
                apply.getId(), target.getDesc(), UserContext.getUser());
        // ② 事务外做后续动作
        if (target == RefundStatus.REJECT) {
            // 拒绝 → 钱没动，把明细与父单**回滚**（这是常见实现缺失的一步：
            //   它的订单会永远卡在"申请退款"状态，用户再也退不了这门课）
            rollback(apply.getOrderDetailId(), apply.getOrderId());
        } else {
            // 同意 → 向支付侧发起退款（外部调用，必须在事务外）
            sendRefundRequest(apply.getId());
        }
    }

    @Override
    public void cancel(RefundCancelDTO dto) {
        // 定位申请：优先按 id，其次按明细取最近一次。
        // ⚠️ 用中间变量转一手：下面的事务 lambda 只能引用"实际上的最终变量"，
        //    如果直接对 apply 反复赋值会编译不过
        RefundApply found;
        if (dto.getId() != null) {
            found = getById(dto.getId());
        } else if (dto.getOrderDetailId() != null) {
            List<RefundApply> list = baseMapper.listByDetailId(dto.getOrderDetailId());
            found = list.isEmpty() ? null : list.get(0);
        } else {
            found = null;
        }
        final RefundApply apply = found;
        if (apply == null) {
            throw new BizIllegalException(TradeErrorInfo.Msg.REFUND_APPLY_NOT_EXISTS);
        }
        // 只能撤自己的
        if (!Objects.equals(apply.getUserId(), UserContext.getUser())) {
            throw new BizIllegalException(TradeErrorInfo.Msg.REFUND_NOT_MINE);
        }
        if (!Objects.equals(apply.getStatus(), RefundStatus.UN_APPROVE.getValue())) {
            throw new BizIllegalException(TradeErrorInfo.Msg.REFUND_CANNOT_CANCEL);
        }
        Integer n = transactionTemplate.execute(st -> baseMapper.changeStatus(
                apply.getId(), RefundStatus.UN_APPROVE.getValue(),
                RefundStatus.CANCEL.getValue(), RefundStatus.CANCEL.getDesc()));
        if (n == null || n == 0) {
            throw new BizIllegalException(TradeErrorInfo.Msg.REFUND_APPLY_APPROVED);
        }
        // ★ 这里就是常见实现那个 bug 的位置：它的撤销逻辑里
        //   `detailService.updateRefundStatusById(r.getOrderDetailId(), ...)` 传的是
        //   一个刚 new 出来、从没 setOrderDetailId 的对象 → 永远传 null → 子订单状态静默不更新。
        //   我们用 apply.getOrderDetailId()（从库里读出来的真实值），不可能为空。
        rollback(apply.getOrderDetailId(), apply.getOrderId());
        log.info("学员撤销退款申请：applyId={}, detailId={}", apply.getId(), apply.getOrderDetailId());
    }

    /** 回滚：明细回到"已支付"，父单状态重新聚合 */
    private void rollback(Long detailId, Long orderId) {
        transactionTemplate.executeWithoutResult(st -> {
            detailMapper.changeStatus(detailId, TradeConstants.DETAIL_REFUNDING, TradeConstants.DETAIL_PAYED);
            orderService.syncRefundStatus(orderId);
        });
    }

    // ==================== ③ 退款结果（MQ 消费入口） ====================

    @Override
    public boolean handleRefundResult(RefundResultMessage msg) {
        if (msg == null || msg.getBizRefundOrderNo() == null) {
            log.warn("收到无效的退款结果消息：{}", msg);
            return false;
        }
        long applyId;
        try {
            applyId = Long.parseLong(msg.getBizRefundOrderNo());
        } catch (NumberFormatException e) {
            log.warn("退款结果消息里的业务单号非法：{}", msg.getBizRefundOrderNo());
            return false;
        }
        RefundApply apply = getById(applyId);
        if (apply == null) {
            log.warn("退款结果找不到对应的申请：applyId={}", applyId);
            return false;
        }
        // 「退款中」：只回填渠道单号，状态不变（等回调或补偿任务）
        if (msg.getStatus() != null && msg.getStatus() == RefundResultMessage.RUNNING) {
            lambdaUpdate().set(RefundApply::getRefundOrderNo, msg.getRefundOrderNo())
                    .eq(RefundApply::getId, applyId).update();
            return false;
        }
        RefundStatus target = (msg.getStatus() != null && msg.getStatus() == RefundResultMessage.SUCCESS)
                ? RefundStatus.SUCCESS : RefundStatus.FAILED;
        // ★ 条件更新（WHERE status = 同意退款）：回调与补偿撞车时只有一次能结清
        Integer n = transactionTemplate.execute(st -> baseMapper.markRefundResult(
                applyId, RefundStatus.AGREE.getValue(), target.getValue(), target.getDesc(),
                msg.getRefundOrderNo(), msg.getRefundChannel(), msg.getFailReason()));
        if (n == null || n == 0) {
            log.info("退款申请已终结，重复结果消息被忽略（幂等）：applyId={}", applyId);
            return false;
        }
        TradeOrderDetail detail = detailMapper.selectById(apply.getOrderDetailId());
        if (target == RefundStatus.SUCCESS) {
            detailMapper.changeStatus(apply.getOrderDetailId(),
                    TradeConstants.DETAIL_REFUNDING, TradeConstants.DETAIL_REFUNDED);
            orderService.syncRefundStatus(apply.getOrderId());
            // ★ 发业务事件：learning 撤销课表、message 发"退款到账"通知
            rabbitMqHelper.sendAfterCommit(MqConstants.Exchange.LEARNING_EXCHANGE,
                    MqConstants.Key.ORDER_REFUND_KEY,
                    OrderRefundMessage.of(
                            apply.getOrderId(), apply.getId(), apply.getUserId(), apply.getRefundAmount(),
                            detail == null ? List.of() : List.of(detail.getCourseId()),
                            detail == null ? null : detail.getCourseName()));
            log.info("退款成功：applyId={}, userId={}, 金额={}分, 已通知学习侧撤销课程",
                    applyId, apply.getUserId(), apply.getRefundAmount());
        } else {
            // ★ 退款失败也要回滚：钱没退成，用户仍然是这门课的主人。
            //   常见实现失败后什么都不做 —— 订单卡在"申请退款"，用户既没退到钱也不能重新申请
            rollback(apply.getOrderDetailId(), apply.getOrderId());
            log.warn("退款失败已回滚订单状态：applyId={}, 原因={}", applyId, msg.getFailReason());
        }
        return true;
    }

    // ==================== ④ 向支付侧发起退款 ====================

    @Override
    public void sendRefundRequest(Long applyId) {
        RefundApply apply = getById(applyId);
        if (apply == null) {
            return;
        }
        if (!Objects.equals(apply.getStatus(), RefundStatus.AGREE.getValue())) {
            // 只有"同意"状态才该发。已是终态 / 被拒绝 / 被撤销 → 什么都不做
            return;
        }
        RefundApplyDTO dto = new RefundApplyDTO()
                .setBizOrderNo(String.valueOf(apply.getOrderId()))
                // ★ 支付侧的幂等键：同一笔申请重复发，支付侧也只会产生一张退款单
                .setBizRefundOrderNo(String.valueOf(apply.getId()))
                .setBizUserId(apply.getUserId())
                .setRefundAmount(apply.getRefundAmount());
        try {
            RefundResultDTO result = payClient.applyRefund(dto);
            if (result == null) {
                // 支付服务不可用（走了降级）。★ 绝不能标成退款失败 ——
                // 钱还没发出去，标失败会让用户看到"退款失败"而实际上它会重试成功
                log.error("支付服务不可用，退款申请保持【同意退款】待重试：applyId={}", applyId);
                return;
            }
            // 回填渠道单号（渠道可能当场成功、也可能回"退款中"）
            lambdaUpdate()
                    .set(result.getRefundOrderNo() != null, RefundApply::getRefundOrderNo, result.getRefundOrderNo())
                    .set(result.getRefundChannel() != null, RefundApply::getRefundChannel, result.getRefundChannel())
                    .eq(RefundApply::getId, applyId)
                    .update();
            log.info("已向支付侧发起退款：applyId={}, 渠道单号={}, 当前状态={}",
                    applyId, result.getRefundOrderNo(), result.getStatus());
        } catch (Exception e) {
            // 异常同样保持"同意"，由 refundRequestJob 重试。
            // 幂等由支付侧的 biz_refund_order_no 唯一索引保证，重复发是安全的
            log.error("发起退款请求异常，保持【同意退款】待重试：applyId={}", applyId, e);
        }
    }

    @Override
    public List<RefundApply> listAgreedPage(int pageNo, int size) {
        Page<RefundApply> page = new Page<>(Math.max(1, pageNo), size);
        return lambdaQuery()
                .eq(RefundApply::getStatus, RefundStatus.AGREE.getValue())
                .gt(RefundApply::getCreateTime, LocalDateTime.now().minusDays(7))
                .orderByAsc(RefundApply::getId)
                .page(page)
                .getRecords();
    }

    // ==================== ⑤ 查询 ====================

    @Override
    public PageDTO<RefundApplyPageVO> queryMyRefunds(RefundApplyPageQuery query) {
        // ★ 学员端强制按当前用户过滤 —— 不让前端传 userId，
        //   否则改个参数就能看到别人的退款记录
        return doPage(query, UserContext.getUser());
    }

    @Override
    public PageDTO<RefundApplyPageVO> queryAdminPage(RefundApplyPageQuery query) {
        return doPage(query, null);
    }

    private PageDTO<RefundApplyPageVO> doPage(RefundApplyPageQuery query, Long userId) {
        Page<RefundApply> page = lambdaQuery()
                .eq(userId != null, RefundApply::getUserId, userId)
                .eq(query.getId() != null, RefundApply::getId, query.getId())
                .eq(query.getOrderId() != null, RefundApply::getOrderId, query.getOrderId())
                .eq(query.getOrderDetailId() != null, RefundApply::getOrderDetailId, query.getOrderDetailId())
                .eq(query.getRefundStatus() != null, RefundApply::getStatus, query.getRefundStatus())
                .orderByDesc(RefundApply::getId)
                .page(query.toMpPage());
        List<RefundApply> records = page.getRecords();
        if (records.isEmpty()) {
            return PageDTO.empty(page);
        }
        // 批量取课程名（明细里的快照）
        List<RefundApplyPageVO> list = new ArrayList<>(records.size());
        for (RefundApply r : records) {
            TradeOrderDetail detail = detailMapper.selectById(r.getOrderDetailId());
            list.add(new RefundApplyPageVO()
                    .setId(r.getId())
                    .setOrderId(r.getOrderId())
                    .setOrderDetailId(r.getOrderDetailId())
                    .setCourseName(detail == null ? null : detail.getCourseName())
                    .setRefundAmount(r.getRefundAmount())
                    .setRefundReason(r.getRefundReason())
                    .setStatus(r.getStatus())
                    .setStatusDesc(RefundStatus.desc(RefundStatus.of(r.getStatus())))
                    .setCreateTime(r.getCreateTime())
                    .setApproveTime(r.getApproveTime())
                    .setFinishTime(r.getFinishTime()));
        }
        return PageDTO.of(page, list);
    }

    @Override
    public RefundApplyVO queryDetail(Long id) {
        RefundApply apply = getById(id);
        if (apply == null) {
            throw new BizIllegalException(TradeErrorInfo.Msg.REFUND_APPLY_NOT_EXISTS);
        }
        return toDetailVO(apply);
    }

    @Override
    public RefundApplyVO queryDetailByDetailId(Long detailId) {
        List<RefundApply> list = baseMapper.listByDetailId(detailId);
        if (list.isEmpty()) {
            return null;
        }
        // 列表已经是 id 倒序，第一条就是最近一次
        return toDetailVO(list.get(0));
    }

    @Override
    public RefundApplyVO nextToApprove() {
        Long id = baseMapper.nextToApprove(RefundStatus.UN_APPROVE.getValue());
        if (id == null) {
            return null;
        }
        return toDetailVO(getById(id));
    }

    private RefundApplyVO toDetailVO(RefundApply apply) {
        RefundApplyVO vo = new RefundApplyVO()
                .setId(apply.getId())
                .setOrderId(apply.getOrderId())
                .setOrderDetailId(apply.getOrderDetailId())
                .setRefundAmount(apply.getRefundAmount())
                .setStatus(apply.getStatus())
                .setStatusDesc(RefundStatus.desc(RefundStatus.of(apply.getStatus())))
                .setMessage(apply.getMessage())
                .setRefundReason(apply.getRefundReason())
                .setQuestionDesc(apply.getQuestionDesc())
                .setCreateTime(apply.getCreateTime())
                .setApproveOpinion(apply.getApproveOpinion())
                .setApproveTime(apply.getApproveTime())
                .setRefundOrderNo(apply.getRefundOrderNo())
                .setRefundChannel(apply.getRefundChannel())
                .setFailedReason(apply.getFailedReason())
                .setFinishTime(apply.getFinishTime());
        // 课程信息取订单明细里的**快照**（不是课程表的当前值）
        TradeOrderDetail detail = detailMapper.selectById(apply.getOrderDetailId());
        if (detail != null) {
            vo.setCourseId(detail.getCourseId())
                    .setCourseName(detail.getCourseName())
                    .setCoverUrl(detail.getCoverUrl())
                    .setPrice(detail.getPrice())
                    .setDiscountAmount(detail.getDiscountAmount())
                    .setRealPayAmount(detail.getRealPayAmount());
        }
        // 原支付信息（对账 / 客服核对用）
        TradeOrder order = orderMapper.selectById(apply.getOrderId());
        if (order != null) {
            vo.setPayOrderNo(order.getPayOrderNo())
                    .setPayChannel(order.getPayChannel())
                    .setOrderTime(order.getCreateTime())
                    .setPaySuccessTime(order.getPayTime());
        }
        return vo;
    }

    /** 供内部调用：清掉某明细的历史（只用于测试环境的数据清理，不暴露接口） */
    @SuppressWarnings("unused")
    private void deleteByDetailId(Long detailId) {
        remove(new LambdaQueryWrapper<RefundApply>().eq(RefundApply::getOrderDetailId, detailId));
    }
}
