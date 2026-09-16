package com.fitmind.data.service;

import java.time.LocalDate;

/**
 * 日数据落库服务（Redis → MySQL）。
 */
public interface IDataFlushService {

    /**
     * 把某天的 Redis 计数落到 MySQL。
     *
     * <p><b>幂等</b>：重复执行只会覆盖出同样的结果 ——
     * Redis 里的计数是"截至当前的累计值"，不是增量，
     * 所以 {@code data_daily} 用「有则更新、无则插入」即可，重复跑不会翻倍。</p>
     *
     * <p>★ 这一点和"消费时去重"是互补的两道保险：
     * 去重保证<b>源头的每一次事件只算一次</b>，
     * 落库用覆盖保证<b>即使任务被重复触发，结果也不会错</b>。</p>
     *
     * @param date 要落库的日期；传 null 表示昨天
     * @return 落了多少天（0 或 1）
     */
    int flushDaily(LocalDate date);

    /**
     * 清理某天的 Redis 计数（落库完成后调用）。
     *
     * <p>为什么落完就清：这些 key 的唯一用途是"当天还没落库的增量"，
     * 落完就没用了。留着只会占内存，且下次扫到会重复落（虽然幂等，但白白写库）。</p>
     */
    void clearCounter(LocalDate date);
}
