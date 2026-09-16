package com.fitmind.trade.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 购物车条目。
 *
 * <p>为什么放 MySQL 而不是 Redis：购物车是<b>需要持久化的用户意图</b>——
 * 用户加进购物车、关掉 App、三天后回来它还得在。Redis 做不了这个保证，
 * 而且购物车天然有 {@code (user_id, course_id)} 这个唯一键，关系库更合适。</p>
 *
 * <p>⚠️ 这里的 {@code price} 只是<b>加入时的展示价</b>，下单时<b>必须回源取课程最新价</b>，
 * 绝不能用购物车里这个数算钱。</p>
 */
@Data
@Accessors(chain = true)
@TableName("trade_cart")
public class TradeCart implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long courseId;

    /** 课程名称（展示用，加入时的快照） */
    private String courseName;

    private String coverUrl;

    /** 加入时的价格（分），仅展示 */
    private Integer price;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
