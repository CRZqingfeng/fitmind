package com.fitmind.message.job;

import com.fitmind.message.service.IUserInboxService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 过期站内信清理（XXL-JOB，每日 3 点）。
 *
 * <p><b>为什么需要它</b>：站内信是"只增不减"的表 —— 每笔支付、每笔退款、
 * 每条公告展开都会写进来。运营发几轮公告、跑几轮验收，表就会持续膨胀。
 * 而其中绝大多数记录早就过了展示期（查询时会被 {@code expire_time} 过滤掉），
 * 属于"占着空间但永远看不到"的数据。</p>
 *
 * <p><b>★ 清理动作的三个保守设计</b>（清理是危险操作，宁少勿错）：</p>
 * <ol>
 *   <li><b>只清"已读"的</b>：未读消息哪怕过期也留着 —— 用户可能正要去看</li>
 *   <li><b>只清"过期超过 7 天"的</b>：不清理刚过期的。用户上周末划到一半
 *       没点开的消息，不该因为跨了个凌晨就消失</li>
 *   <li><b>返回并记录实际删除条数</b>：定时任务的返回值会进 XXL-JOB 的执行日志，
 *       "清理了 0 条"和"清理失败"是两件事，不能都表现为"没有输出"
 *       （这条经验来自验收脚本：<b>任何清理动作都要检查它自己是否成功</b>）</li>
 * </ol>
 *
 * <p>路由策略用 FAILOVER 而不是分片广播：清理是同一条 SQL 条件删，
 * 多个实例分片反而不好切（而且正确性靠的是条件本身，不靠"只跑一次"）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageCleanupJob {

    private final IUserInboxService inboxService;

    @XxlJob("messageCleanupJob")
    public void cleanupExpiredMessage() {
        int n = inboxService.cleanupExpired();
        String msg = String.format("过期站内信清理完成：删除 %d 条（条件：过期超过 7 天且已读）", n);
        XxlJobHelper.log(msg);
        log.info("[XXL-JOB] {}", msg);
    }
}
