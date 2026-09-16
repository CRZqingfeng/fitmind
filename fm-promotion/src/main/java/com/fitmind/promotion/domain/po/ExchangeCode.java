package com.fitmind.promotion.domain.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 兑换码。
 *
 * <p><b>★ {@code id} 不是自增主键，而是全局序列号</b> ——
 * 它由 Redis 的 {@code INCRBY} 一次性分配一段，
 * 这样"码 → 序号 → 属于哪张券"只需一次主键查询，
 * 也不需要为每个码单独插一条记录去维护自增关系。</p>
 */
@Data
@Accessors(chain = true)
@TableName("exchange_code")
public class ExchangeCode implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id")
    private Long id;

    /** 兑换码（Base32，10 位） */
    private String code;

    /** 对应的券id */
    private Long exchangeTargetId;

    private LocalDateTime expiredTime;

    /** 1-未兑换 2-已兑换 */
    private Integer status;

    private Long userId;

    private LocalDateTime exchangeTime;

    private LocalDateTime createTime;
}
