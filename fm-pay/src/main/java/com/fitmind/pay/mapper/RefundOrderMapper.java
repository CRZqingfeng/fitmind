package com.fitmind.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.pay.domain.po.RefundOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 退款单 Mapper。
 *
 * <p>与 {@link PayOrderMapper} 完全同构：查单 + <b>条件更新</b>。
 * 条件更新是这个模块所有幂等的地基。</p>
 */
@Mapper
public interface RefundOrderMapper extends BaseMapper<RefundOrder> {

    /**
     * ★ 按业务退款单号查（幂等查询的主入口）。
     *
     * <p>因为 {@code uk_biz_refund_order_no} 是唯一索引，这里最多返回一条。</p>
     */
    @Select("SELECT * FROM refund_order WHERE biz_refund_order_no = #{bizRefundOrderNo}")
    RefundOrder getByBizRefundOrderNo(@Param("bizRefundOrderNo") String bizRefundOrderNo);

    @Select("SELECT * FROM refund_order WHERE refund_order_no = #{refundOrderNo}")
    RefundOrder getByRefundOrderNo(@Param("refundOrderNo") String refundOrderNo);

    /**
     * ★★ 条件更新：只有当前状态等于 expect 才改。
     *
     * <p>挡的是"回调与补偿任务同时结清同一笔退款"这种情况 ——
     * 两个线程都查到"退款中"，都想改成"成功"，
     * 只有先把状态挪走的那个能成功（返回 1），另一个返回 0、不再发 MQ。</p>
     */
    @Update("""
            UPDATE refund_order
            SET status = #{targetStatus}, update_time = NOW()
            WHERE refund_order_no = #{refundOrderNo} AND status = #{expectStatus}
            """)
    int changeStatus(@Param("refundOrderNo") String refundOrderNo,
                     @Param("expectStatus") int expectStatus,
                     @Param("targetStatus") int targetStatus);

    /**
     * 回填渠道的详细回执（渠道标识、返回码、描述、完成时间）。
     *
     * <p>与 {@link #changeStatus} 分开的理由和支付侧一样：
     * 那是<b>并发闸门</b>，这是<b>补充信息</b>。混在一起会让"改了状态但没写回执"
     * 这种中间态变得难以判断。</p>
     */
    @Update("""
            UPDATE refund_order
            SET refund_channel = #{refundChannel},
                result_code = #{resultCode},
                result_msg = #{resultMsg},
                refund_amount = #{refundAmount},
                finish_time = NOW()
            WHERE refund_order_no = #{refundOrderNo}
            """)
    int markFinish(@Param("refundOrderNo") String refundOrderNo,
                   @Param("refundChannel") String refundChannel,
                   @Param("resultCode") String resultCode,
                   @Param("resultMsg") String resultMsg,
                   @Param("refundAmount") Integer refundAmount);

    /**
     * 捞"退款中"的单子给补偿任务用。
     *
     * <p>按 id 升序：先处理老的，避免老单子被新单子一直挤到后面。</p>
     */
    /**
     * 捞"退款中"的单子给补偿任务用（<b>分页</b>）。
     *
     * <p>按 id 升序：先处理老的 —— 一笔卡了很久的退款比刚发起的更该被对账，
     * 不能因为新单子不断产生就把老单子永远挤在后面。</p>
     *
     * <p><b>为什么要分页而不是每次捞前 N 条</b>：XXL-JOB 用<b>分片广播</b>时，
     * 每个实例负责不同页 —— 如果都捞"前 N 条"，多实例会重复扫同一批数据，
     * 分片就白分了。用 {@code LIMIT offset, size} 让每片看自己的那一页。</p>
     */
    @Select("""
            SELECT * FROM refund_order
            WHERE status = #{runningStatus}
            ORDER BY id ASC
            LIMIT #{offset}, #{size}
            """)
    List<RefundOrder> listRunningByPage(@Param("runningStatus") int runningStatus,
                                        @Param("offset") int offset,
                                        @Param("size") int size);

    /**
     * ★ 统计某笔业务订单<b>已经成功退出去</b>的金额合计（分）。
     *
     * <p>资金安全用：退款前校验"已退 + 本次 ≤ 原支付额"。
     * 挡的是这个场景 —— 一笔 199 的订单被拆成两笔 199 的退款申请
     * （比如两个订单明细都被误认为该退全额），如果没有这道校验，
     * 就会退出去 398。</p>
     *
     * <p>只统计 {@code status = 成功} 的：失败的和在途的不算，
     * 否则一次失败就会永久占掉额度，用户再也退不了。</p>
     */
    @Select("""
            SELECT COALESCE(SUM(refund_amount), 0) FROM refund_order
            WHERE biz_order_no = #{bizOrderNo} AND status = #{successStatus}
            """)
    Integer sumRefundedAmount(@Param("bizOrderNo") String bizOrderNo,
                              @Param("successStatus") int successStatus);
}
