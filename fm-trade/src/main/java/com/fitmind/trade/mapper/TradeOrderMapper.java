package com.fitmind.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.trade.domain.po.TradeOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单 Mapper。
 */
@Mapper
public interface TradeOrderMapper extends BaseMapper<TradeOrder> {

    /**
     * ★★ 条件更新：<b>只有当前状态等于 expect，才把它改成 target</b>。
     *
     * <p>这是整个交易模块的地基。所有状态变更（支付成功、超时关闭、用户取消）
     * 都必须走它，原因有两个：</p>
     * <ol>
     *   <li><b>并发安全</b>：支付回调和超时取消是两个并发流程，都可能读到"待支付"，
     *       但只有一个人能改成功。{@code WHERE status = NO_PAY} 就是那道闸</li>
     *   <li><b>天然幂等</b>：MQ 是 at-least-once，同一条"支付成功"消息可能投递两次。
     *       第二次执行时状态已经是 PAYED，条件不成立返回 0 —— 调用方据此跳过后续动作
     *       （比如不再发通知、不再加课表）。常见实现正因为没有这一步，
     *       MQ 重投会重复加课</li>
     * </ol>
     *
     * @return 1 = 改成功了（说明这次调用是"第一个"）；0 = 状态不匹配，什么都没做
     */
    @Update("""
            <script>
            UPDATE trade_order
            SET status = #{o.status}, update_time = NOW()
            <if test="o.payTime != null">, pay_time = #{o.payTime}</if>
            <if test="o.closeTime != null">, close_time = #{o.closeTime}</if>
            <if test="o.payChannel != null">, pay_channel = #{o.payChannel}</if>
            <if test="o.payOrderNo != null">, pay_order_no = #{o.payOrderNo}</if>
            <if test="o.message != null">, message = #{o.message}</if>
            WHERE id = #{o.id} AND status = #{expectStatus}
            </script>
            """)
    int changeStatus(@Param("o") TradeOrder order, @Param("expectStatus") int expectStatus);

    /** 读订单使用的优惠券id（JSON 列的原始文本） */
    @Select("SELECT coupon_ids FROM trade_order WHERE id = #{orderId}")
    String queryCouponIds(@Param("orderId") Long orderId);

    /**
     * 兜底扫描：找出「创建时间早于 deadline 且仍是待支付」的订单。
     *
     * <p>延迟队列（Redis ZSet）是主路径，但 Redis 重启/被清空时队列就丢了。
     * 这个查询是保险绳 —— 走 {@code idx_status_create} 索引，很轻。</p>
     */
    @Select("""
            SELECT * FROM trade_order
            WHERE status = #{status} AND create_time < #{deadline}
            ORDER BY create_time
            LIMIT #{limit}
            """)
    List<TradeOrder> queryTimeoutOrders(@Param("status") int status,
                                        @Param("deadline") LocalDateTime deadline,
                                        @Param("limit") int limit);
}
