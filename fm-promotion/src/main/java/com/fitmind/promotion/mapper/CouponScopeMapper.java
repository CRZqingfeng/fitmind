package com.fitmind.promotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fitmind.promotion.domain.po.CouponScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CouponScopeMapper extends BaseMapper<CouponScope> {

    /**
     * 查一批券各自限定了哪些业务id（课程/分类）。
     * <p>一次查完，避免每张券查一次（N+1）。</p>
     */
    @Select("""
            <script>
            SELECT coupon_id, biz_id FROM coupon_scope
            WHERE biz_type = #{bizType}
            <if test="couponIds != null and couponIds.size() > 0">
              AND coupon_id IN
              <foreach collection="couponIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            </script>
            """)
    List<ScopeRow> queryScopes(@Param("bizType") int bizType,
                               @Param("couponIds") List<Long> couponIds);

    /** 查询结果的临时载体（不是数据库表） */
    class ScopeRow {
        private Long couponId;
        private Long bizId;

        public Long getCouponId() {
            return couponId;
        }

        public void setCouponId(Long couponId) {
            this.couponId = couponId;
        }

        public Long getBizId() {
            return bizId;
        }

        public void setBizId(Long bizId) {
            this.bizId = bizId;
        }
    }
}
