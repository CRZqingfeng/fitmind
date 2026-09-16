package com.fitmind.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.trade.domain.po.TradeOrderDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 订单明细 Mapper。
 */
@Mapper
public interface TradeOrderDetailMapper extends BaseMapper<TradeOrderDetail> {

    @Select("SELECT * FROM trade_order_detail WHERE order_id = #{orderId} ORDER BY id")
    List<TradeOrderDetail> getByOrderId(@Param("orderId") Long orderId);

    /**
     * 查一批课程里，用户「已购（含已支付）」的那些。
     *
     * <p>下单 / 加购物车都要用它拦重复购买。走
     * {@code idx_user_course_status(user_id, course_id, status)} 索引，一次查一批。</p>
     */
    @Select("""
            <script>
            SELECT DISTINCT course_id FROM trade_order_detail
            WHERE user_id = #{userId}
              AND status = #{status}
              AND course_id IN
              <foreach collection="courseIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> queryBoughtCourseIds(@Param("userId") Long userId,
                                    @Param("courseIds") List<Long> courseIds,
                                    @Param("status") int status);

    /**
     * 查一批课程里，用户「有待支付订单」的那些（提醒用户先把旧单处理掉）。
     */
    @Select("""
            <script>
            SELECT DISTINCT course_id FROM trade_order_detail
            WHERE user_id = #{userId}
              AND status = #{status}
              AND course_id IN
              <foreach collection="courseIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Long> queryPendingCourseIds(@Param("userId") Long userId,
                                     @Param("courseIds") List<Long> courseIds,
                                     @Param("status") int status);

    /**
     * ★ 明细状态<b>条件更新</b>（退款流程专用）。
     *
     * <p>和订单的 {@code changeStatus} 是同一个思路：{@code WHERE status = 我预期的旧状态}。
     * 退款流程里到处是"读-判断-写"，没有这一步，并发的申请 / 撤销 / 回调
     * 就会互相覆盖（比如退款成功后又被撤销改成已支付）。</p>
     *
     * @return 1 = 本次改成功；0 = 状态已不是预期（别人改过了）
     */
    @Update("""
            UPDATE trade_order_detail
            SET status = #{targetStatus}, update_time = NOW()
            WHERE id = #{id} AND status = #{expectStatus}
            """)
    int changeStatus(@Param("id") Long id,
                     @Param("expectStatus") int expectStatus,
                     @Param("targetStatus") int targetStatus);
}
