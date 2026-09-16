package com.fitmind.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.pay.domain.po.PayOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 支付单 Mapper。
 */
@Mapper
public interface PayOrderMapper extends BaseMapper<PayOrder> {

    @Select("SELECT * FROM pay_order WHERE pay_order_no = #{payOrderNo}")
    PayOrder getByPayOrderNo(@Param("payOrderNo") String payOrderNo);

    /**
     * 查某笔业务订单下仍然待支付的支付单（用于"重新发起支付"时复用）。
     */
    @Select("SELECT * FROM pay_order WHERE biz_order_no = #{bizOrderNo} AND status = #{status} ORDER BY id DESC LIMIT 1")
    PayOrder getPendingByBizOrderNo(@Param("bizOrderNo") String bizOrderNo, @Param("status") int status);

    /**
     * 查某笔业务订单下<b>已支付</b>的支付单（退款时用）。
     *
     * <p>与 {@link #getPendingByBizOrderNo} 是同一段 SQL，只是语义更明确 ——
     * 退款场景传 {@code status = 已支付}。之所以新加一个方法而不是复用，
     * 是因为 {@code getPendingByBizOrderNo} 这个名字在退款场景里读起来是反的
     * （"pending" 与"已支付"矛盾），而 M4-2 的代码已经验证过了，不动它更稳。</p>
     */
    @Select("SELECT * FROM pay_order WHERE biz_order_no = #{bizOrderNo} AND status = #{status} ORDER BY id DESC LIMIT 1")
    PayOrder getPaidByBizOrderNo(@Param("bizOrderNo") String bizOrderNo, @Param("status") int status);

    /**
     * ★★ 条件更新：<b>只有当前状态等于 expect，才把它改成 target</b>。
     *
     * <p>这是支付回调幂等的地基。第三方回调可能重复投递（网络重试、
     * 用户重复点击），只有第一次能把"待支付"改成"已支付"并返回 1 ——
     * 调用方据此决定要不要发 MQ（重复回调不该重复通知交易模块）。</p>
     *
     * @return 1 = 本次调用是"第一个"改成功的；0 = 状态已不是预期
     */
    @Update("""
            UPDATE pay_order
            SET status = #{targetStatus}, update_time = NOW()
            WHERE pay_order_no = #{payOrderNo} AND status = #{expectStatus}
            """)
    int changeStatus(@Param("payOrderNo") String payOrderNo,
                     @Param("expectStatus") int expectStatus,
                     @Param("targetStatus") int targetStatus);

    /**
     * 支付成功后回填时间与二维码（与 changeStatus 分开：那是并发闸门，这是补充信息）。
     */
    @Update("""
            UPDATE pay_order
            SET pay_success_time = NOW()
            WHERE pay_order_no = #{payOrderNo}
            """)
    int markPaySuccessTime(@Param("payOrderNo") String payOrderNo);
}
