package com.fitmind.promotion.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.promotion.domain.po.ExchangeCode;

import java.util.List;

/**
 * 兑换码服务。
 */
public interface IExchangeCodeService extends IService<ExchangeCode> {

    /**
     * 为一张券<b>批量生成</b>兑换码。
     *
     * <p>流程：Redis 一次性自增出一段序号 → 用算法算出每个码 → 入库 →
     * 把"该券的最大序号"记进 ZSet。</p>
     *
     * @return 生成的码（用于后台导出）
     */
    List<String> generateCodes(Long couponId);

    /**
     * 查某个序列号属于哪张券。
     *
     * <p>先查 ZSet（Redis），查不到再回数据库 —— 因为 Redis 可能被清空。</p>
     */
    Long queryTargetId(long serialNum);

    /**
     * 原子地抢占某个序号（bitmap 置位）。
     *
     * <p>★ 返回 <b>true = 本次抢到</b>（此前未被兑换）；false = 已被别人兑换过。
     * 语义是"<b>尝试抢占</b>"而不是"无条件置位"—— 调用方必须按返回值决定是否继续发券，
     * 不能当 void 用。</p>
     *
     * @return 本次是否抢占成功
     */
    boolean markExchanged(long serialNum);

    /**
     * 释放某个序号的占位（补偿操作，与 {@link #markExchanged} 配对）。
     *
     * <p>⚠️ 抢占成功后、发券落库之前出了任何异常，都<b>必须</b>调用本方法把码放回去。
     * 否则这个码会被<b>永久吃掉</b>：券没发出去，用户也不能重试（#A17）。</p>
     */
    void releaseExchanged(long serialNum);
}
