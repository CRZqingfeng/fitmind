package com.fitmind.trade.job;

import com.fitmind.trade.domain.po.RefundApply;
import com.fitmind.trade.service.IRefundApplyService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 退款请求补发任务（XXL-JOB，每 2 分钟）。
 *
 * <p><b>★ 它兜的是什么</b>：审批"同意"之后，交易侧要调支付服务发起退款。
 * 那次调用可能失败 —— 支付服务正好重启、网络抖动、Feign 超时。
 * 一旦失败，这笔申请就停在"同意退款"状态：<b>审批过了但钱没动</b>。</p>
 *
 * <p>所以需要一个定时任务扫"同意退款"的申请，把没发成功的补发一遍。
 * 与 M2 里"课程上架消息发失败"的区别在于：这里的事是<b>钱</b>，
 * 不能只记个日志等人工发现。</p>
 *
 * <p><b>为什么这个任务是安全的（重复发不会退两次）</b>：
 * 支付侧的 {@code refund_order} 上有 {@code uk_biz_refund_order_no} 唯一索引
 * （值 = 本申请的 id）。同一笔申请重复发多少次，支付侧都只会产生一张退款单，
 * 第二次及以后走的是"查询已存在"的分支。这层幂等在设计时就放在支付侧了 ——
 * <b>因为"重试"是分布式的常态，幂等键必须落在被调方</b>。</p>
 *
 * <p>用分片广播 + 页码取数，与 M3-2 / M4-4 的其它补偿任务一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundRequestJob {

    private final IRefundApplyService refundApplyService;

    @XxlJob("refundRequestJob")
    public void refundRequest() {
        int pageNo = XxlJobHelper.getShardIndex() + 1;
        String jobParam = XxlJobHelper.getJobParam();
        int size = StringUtils.hasText(jobParam) && jobParam.chars().allMatch(Character::isDigit)
                ? Integer.parseInt(jobParam) : 10;
        List<RefundApply> list = refundApplyService.listAgreedPage(pageNo, size);
        if (list.isEmpty()) {
            XxlJobHelper.log("第 {} 片没有待发起的退款申请", pageNo);
            return;
        }
        int sent = 0;
        for (RefundApply apply : list) {
            try {
                // 内部会先判断状态是否仍是"同意"，不是则直接跳过
                refundApplyService.sendRefundRequest(apply.getId());
                sent++;
            } catch (Exception e) {
                log.error("补发退款请求失败：applyId={}", apply.getId(), e);
            }
        }
        String msg = String.format("退款请求补发完成：第 %d 片，待发 %d 笔，尝试 %d 笔",
                pageNo, list.size(), sent);
        XxlJobHelper.log(msg);
        log.info("[XXL-JOB] {}", msg);
    }
}
