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

    /** 标记某个序号已兑换（bitmap） */
    boolean markExchanged(long serialNum);
}
