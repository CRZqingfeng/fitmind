package com.fitmind.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.trade.domain.po.RefundApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 退款申请 Mapper。
 *
 * <p><b>★ 所有状态变更都走条件更新</b>（{@code WHERE status = 预期旧状态}）。
 * 这不是"多加一个条件"，而是这个模块正确性的地基：
 * 审批是"读-判断-写"三步，如果不在写的时候再验一次旧状态，
 * 两个管理员同时点"同意"就会<b>都通过</b>（都从"待审批"改到"同意"），
 * 于是同一笔退款被发两次。</p>
 */
@Mapper
public interface RefundApplyMapper extends BaseMapper<RefundApply> {

    /**
     * 审批（同意 / 拒绝）—— <b>审批人信息与状态在同一次更新里写入</b>。
     *
     * <p>为什么合并成一条 SQL：如果先改状态、再补审批人，中间那段时间里
     * 这条记录是"已同意但没人审"的；而且补写那步不做条件校验的话，
     * 被并发抢输的那个请求也能覆盖审批人。合并 + 条件更新，
     * 保证"谁抢到了谁就是审批人"。</p>
     *
     * @return 1 = 本次抢到了审批权；0 = 状态已变（别人审过了）
     */
    @Update("""
            UPDATE refund_apply
            SET status = #{targetStatus},
                message = #{message},
                approver = #{approver},
                approve_opinion = #{opinion},
                approve_time = NOW()
            WHERE id = #{id} AND status = #{expectStatus}
            """)
    int approve(@Param("id") Long id,
                @Param("expectStatus") int expectStatus,
                @Param("targetStatus") int targetStatus,
                @Param("message") String message,
                @Param("approver") Long approver,
                @Param("opinion") String opinion);

    /** 通用状态变更（学员撤销、退款失败重试等） */
    @Update("""
            UPDATE refund_apply
            SET status = #{targetStatus}, message = #{message}
            WHERE id = #{id} AND status = #{expectStatus}
            """)
    int changeStatus(@Param("id") Long id,
                     @Param("expectStatus") int expectStatus,
                     @Param("targetStatus") int targetStatus,
                     @Param("message") String message);

    /**
     * ★ 写入渠道退款结果（终态）—— 同样带条件更新。
     *
     * <p>挡的是"回调与补偿任务同时结清"：两者都读到"同意退款"，
     * 都想去改成"成功"，只有先执行的那个能改成功（返回 1），
     * 另一个返回 0 → 它就不该再往下走（不重复改订单、不重复撤销报名）。</p>
     */
    @Update("""
            UPDATE refund_apply
            SET status = #{targetStatus},
                message = #{message},
                refund_order_no = #{refundOrderNo},
                refund_channel = #{refundChannel},
                failed_reason = #{failedReason},
                finish_time = NOW()
            WHERE id = #{id} AND status = #{expectStatus}
            """)
    int markRefundResult(@Param("id") Long id,
                         @Param("expectStatus") int expectStatus,
                         @Param("targetStatus") int targetStatus,
                         @Param("message") String message,
                         @Param("refundOrderNo") String refundOrderNo,
                         @Param("refundChannel") String refundChannel,
                         @Param("failedReason") String failedReason);

    /** 某明细的申请历史（倒序，最新的在最前） */
    @Select("SELECT * FROM refund_apply WHERE order_detail_id = #{detailId} ORDER BY id DESC")
    List<RefundApply> listByDetailId(@Param("detailId") Long detailId);

    /** 某明细申请过几次（业务规则：学员最多 2 次） */
    @Select("SELECT COUNT(*) FROM refund_apply WHERE order_detail_id = #{detailId}")
    int countByDetailId(@Param("detailId") Long detailId);

    /**
     * 某明细<b>已经成功退出去</b>的金额合计（分）。
     *
     * <p>资金安全校验：再次申请时，"已成功退款 + 本次" 不得超过明细实付。
     * 只算成功的 —— 失败/取消的不占额度，否则一次失败就永久锁死这门课。</p>
     */
    @Select("""
            SELECT COALESCE(SUM(refund_amount), 0) FROM refund_apply
            WHERE order_detail_id = #{detailId} AND status = #{successStatus}
            """)
    Integer sumSuccessAmountByDetailId(@Param("detailId") Long detailId,
                                       @Param("successStatus") int successStatus);

    /** 下一个待审批的申请 id（供管理端"逐条审批"用）。没有则返回 null */
    @Select("""
            SELECT id FROM refund_apply
            WHERE status = #{status}
            ORDER BY id ASC LIMIT 1
            """)
    Long nextToApprove(@Param("status") int status);
}
