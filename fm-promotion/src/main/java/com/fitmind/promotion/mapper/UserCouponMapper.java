package com.fitmind.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.promotion.domain.po.UserCoupon;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /**
     * 查我的**未使用且未过期**的券（关联券模板，一次拿出算优惠要用的全部字段）。
     *
     * <p>这是"叠加推荐"的输入 —— 只查未使用的，已用/已失效的不参与计算。</p>
     */
    @Select("""
            SELECT c.*, uc.id AS userCouponId FROM user_coupon uc
            JOIN coupon c ON uc.coupon_id = c.id
            WHERE uc.user_id = #{userId}
              AND uc.status = 1
              AND (uc.term_end_time IS NULL OR uc.term_end_time > NOW())
            ORDER BY uc.id
            """)
    List<com.fitmind.promotion.domain.po.Coupon> queryMyAvailableCoupons(@Param("userId") Long userId);

    /**
     * 按用户券id查券模板（核销时用）。
     *
     * <p>⚠️ <b>注解里的动态 SQL 必须用 {@code <script>} 整段包裹</b>：
     * 少了它，{@code <foreach>} 会被当成普通文本，{@code #{id}} 也不会被识别成参数，
     * 运行时报 {@code BindingException: Parameter 'id' not found}。
     * 这个错要到<b>第一次调用</b>才暴露（编译期完全正常），所以很容易漏。</p>
     */
    @Select("""
            <script>
            SELECT c.*, uc.id AS userCouponId FROM user_coupon uc
            JOIN coupon c ON uc.coupon_id = c.id
            WHERE uc.id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
              AND uc.user_id = #{userId}
              AND uc.status = 1
            </script>
            """)
    List<com.fitmind.promotion.domain.po.Coupon> queryByUserCouponIds(
            @Param("ids") List<Long> ids, @Param("userId") Long userId);
}
