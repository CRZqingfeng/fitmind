package com.fitmind.exam.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 体测记录（<b>不可变快照</b>）。
 *
 * <p>★ 它与 {@code fm_user.user_body_profile} 的区别是本模块最重要的设计：</p>
 * <ul>
 *   <li>{@code user_body_profile} = <b>当前状态</b>（覆盖式更新，回答"我现在什么样"）</li>
 *   <li>本表 = <b>历史轨迹</b>（每次体测一条、不可变，回答"我练得怎么样"）</li>
 * </ul>
 *
 * <p>两者都存身高体重，看似冗余，但语义完全不同：
 * 档案被"改"了就没有历史，而体测的价值恰恰在<b>能对比</b>。
 * 这和 M4 的订单明细存课程快照是同一个道理 ——
 * 订单是交易凭证，体测是训练凭证，都必须是"当时"的样子。</p>
 *
 * <p>★ 所有"算出来的"字段（bmi / whr / bmr / tdee / 各项分级 / score）
 * 都由服务端计算后写入，<b>不接受前端传值</b>。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("body_assessment")
public class BodyAssessment implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    // ---------- 录入值 ----------

    /** 性别快照：0-男 1-女 2-未知 */
    private Integer gender;
    private BigDecimal height;
    private BigDecimal weight;
    private BigDecimal targetWeight;
    private BigDecimal bodyFatRate;
    private BigDecimal waist;
    private BigDecimal chest;
    private BigDecimal arm;
    private BigDecimal hip;

    // ---------- 服务端计算（★ 不接前端传值） ----------

    private BigDecimal bmi;
    private Integer bmiLevel;
    private Integer fatLevel;
    private BigDecimal whr;
    private Integer whrLevel;
    private Integer bmr;
    private Integer tdee;
    private BigDecimal leanMass;
    private BigDecimal fatMass;
    private Integer score;

    // ---------- 结论 ----------

    private Integer goalType;
    /** 一句话结论 */
    private String summary;
    /** 训练建议（结构化文本） */
    private String suggestion;

    /**
     * 体测时间。
     *
     * <p>★ 与 create_time 分开：用户可能<b>补录</b>上周的体测数据，
     * 那时"记录创建时间"和"体测发生时间"不是一个东西。</p>
     */
    private LocalDateTime assessTime;

    private LocalDateTime createTime;
}
