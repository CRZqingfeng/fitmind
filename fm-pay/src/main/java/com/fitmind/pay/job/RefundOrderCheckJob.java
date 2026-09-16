package com.fitmind.pay.job;

import com.fitmind.pay.domain.po.RefundOrder;
import com.fitmind.pay.service.IRefundOrderService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 退款在途对账任务（XXL-JOB，每 2 分钟）。
 *
 * <p><b>★ 为什么这个任务不是"可选优化"，而是必需</b>：退款结果有<b>两条来源</b> ——</p>
 * <ol>
 *   <li>渠道回调（推）—— 快，但<b>会丢</b>：网络抖动、我们的服务正在重启、
 *       渠道自己重试次数用尽，任何一环都可能让一条回调永远不到</li>
 *   <li>本任务主动查询（拉）—— 慢，但<b>不会丢</b>：只要单子还在"退款中"，
 *       就会被反复问到有结果为止</li>
 * </ol>
 *
 * <p>只做①的系统会出现一种很难查的账目差异：<b>渠道那边钱已经退了，
 * 我们这边永远显示"退款中"</b>，而且永远不会自愈 —— 用户来问，客服也没有依据。
 * 这不是理论风险，异步回调丢失在实际生产里是常态。</p>
 *
 * <p><b>路由策略：分片广播</b>（与 M3-2 的榜单结算同一思路）。
 * 所以取数据要用<b>页码</b>（{@code 分片序号 + 1}）而不是"前 N 条"，
 * 否则多个实例会重复扫描同一批数据。正确性不依赖"只被处理一次" ——
 * 结清逻辑是条件更新（{@code WHERE status = 退款中}），重复执行天然无副作用。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundOrderCheckJob {

    private final IRefundOrderService refundOrderService;

    @XxlJob("refundOrderCheckJob")
    public void checkRefundOrder() {
        // 1. 分片序号 + 1 作为页码（分片广播下每个实例看到不同的一页）
        int pageNo = XxlJobHelper.getShardIndex() + 1;
        // 2. 每片处理条数：默认 20，可由任务参数覆盖（调大 = 单次对账更多，但更慢）
        String jobParam = XxlJobHelper.getJobParam();
        int size = StringUtils.hasText(jobParam) && jobParam.chars().allMatch(Character::isDigit)
                ? Integer.parseInt(jobParam) : 20;
        // 3. 捞出这一页"退款中"的单子
        List<RefundOrder> list = refundOrderService.listRunningPage(pageNo, size);
        if (list.isEmpty()) {
            XxlJobHelper.log("第 {} 片没有待对账的退款单", pageNo);
            return;
        }
        // 4. 逐个主动问渠道。queryAndSync 内部：
        //    - 渠道还在处理 → 什么都不做（下轮再问）
        //    - 已经成功/失败 → 走和回调完全相同的结清逻辑（含发 MQ）
        int settled = 0;
        for (RefundOrder ro : list) {
            try {
                if (refundOrderService.queryAndSync(ro.getRefundOrderNo())) {
                    settled++;
                }
            } catch (Exception e) {
                // 单笔失败不影响其它单子：渠道偶发异常是正常的，下一轮会重试
                log.error("退款对账失败：refundOrderNo={}", ro.getRefundOrderNo(), e);
            }
        }
        String msg = String.format("退款对账完成：第 %d 片，扫描 %d 笔，结清 %d 笔", pageNo, list.size(), settled);
        XxlJobHelper.log(msg);
        log.info("[XXL-JOB] {}", msg);
    }
}
