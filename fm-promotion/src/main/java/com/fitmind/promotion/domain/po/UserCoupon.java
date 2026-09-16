package com.fitmind.promotion.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户券（用户领到手的那一张）。
 */
@Data
@Accessors(chain = true)
@TableName("user_coupon")
public class UserCoupon implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    private Long couponId;

    private Long userId;

    /** 1-未使用 2-已使用 3-已失效 */
    private Integer status;

    private LocalDateTime termBeginTime;

    private LocalDateTime termEndTime;

    /** 核销在哪个订单 */
    private Long orderId;

    private LocalDateTime usedTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
