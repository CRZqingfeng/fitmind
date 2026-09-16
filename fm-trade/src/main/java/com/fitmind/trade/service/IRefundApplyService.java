package com.fitmind.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.mq.message.RefundResultMessage;
import com.fitmind.trade.domain.dto.ApproveFormDTO;
import com.fitmind.trade.domain.dto.RefundCancelDTO;
import com.fitmind.trade.domain.dto.RefundFormDTO;
import com.fitmind.trade.domain.po.RefundApply;
import com.fitmind.trade.domain.query.RefundApplyPageQuery;
import com.fitmind.trade.domain.vo.RefundApplyPageVO;
import com.fitmind.trade.domain.vo.RefundApplyVO;

import java.util.List;

/**
 * 退款申请服务。
 *
 * <p><b>★ 退款的两个入口，最终汇到同一条链路</b>：</p>
 * <ul>
 *   <li>{@link #applyRefund} —— 学员申诉（需要管理员审批）</li>
 *   <li>{@link #adminRefund} —— 管理员直接退（跳过审批，等同于"申请即同意"）</li>
 * </ul>
 * <p>两者的差别只在初始状态（待审批 / 同意），之后的流转完全一样 ——
 * 这样"审批同意之后要做什么"只有一处实现，不会出现"学员退款和客服退款走的路径不同"。</p>
 */
public interface IRefundApplyService extends IService<RefundApply> {

    /**
     * 学员申请退款。
     *
     * @return 退款申请 id
     */
    Long applyRefund(RefundFormDTO dto);

    /**
     * 管理员直接退款（无需审批）。
     *
     * @param orderDetailId 要退的订单明细
     * @param reason        退款原因（记在申请单上，便于事后追溯）
     * @return 退款申请 id
     */
    Long adminRefund(Long orderDetailId, String reason);

    /** 审批（同意 / 拒绝）。同意后会立即向支付侧发起退款 */
    void approve(ApproveFormDTO dto);

    /** 学员撤销申请（只有待审批的能撤） */
    void cancel(RefundCancelDTO dto);

    /** 我的退款申请分页 */
    PageDTO<RefundApplyPageVO> queryMyRefunds(RefundApplyPageQuery query);

    /** 管理端退款申请分页 */
    PageDTO<RefundApplyPageVO> queryAdminPage(RefundApplyPageQuery query);

    /** 退款详情 */
    RefundApplyVO queryDetail(Long id);

    /** 按订单明细查最近一次退款（"我的课程"页面用） */
    RefundApplyVO queryDetailByDetailId(Long detailId);

    /** 下一个待审批的申请（管理端逐条审批用；没有则返回 null） */
    RefundApplyVO nextToApprove();

    /**
     * ★ 处理支付侧回传的退款结果（MQ 消费）。
     *
     * <p>成功 → 明细置为已退款、聚合父单、通知学习侧撤销报名；
     * 失败 → 明细与父单<b>回滚</b>（钱没退成，用户还是这门课的主人）。</p>
     *
     * @return true = 本次真的处理了（首次）；false = 已处理过（幂等）
     */
    boolean handleRefundResult(RefundResultMessage msg);

    /** 分页捞"已同意但还没发出去"的申请（兜底重试用） */
    List<RefundApply> listAgreedPage(int pageNo, int size);

    /** 向支付侧发起退款申请（审批同意后调用；失败时保持"同意"待重试） */
    void sendRefundRequest(Long applyId);
}
