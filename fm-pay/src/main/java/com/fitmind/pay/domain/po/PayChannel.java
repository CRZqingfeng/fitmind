package com.fitmind.pay.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 支付渠道。
 */
@Data
@Accessors(chain = true)
@TableName("pay_channel")
public class PayChannel implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 渠道码（同时是渠道实现类的 Bean 名） */
    private String channelCode;

    private String name;

    /** 是否启用：0-否 1-是 */
    private Integer enabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
