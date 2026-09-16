package com.fitmind.remark.job;

import com.fitmind.common.constants.MqConstants;
import com.fitmind.remark.service.ILikedRecordService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 点赞数同步任务（XXL-JOB，每 20 秒）。
 *
 * <p>常见实现用 {@code @Scheduled(fixedDelay = 20000)}：JVM 内、无执行记录、
 * 多实例时各自跑（靠 popMin 的原子性侥幸不重复）。我们换成 XXL-JOB：
 * 执行记录可见、失败可重试、路由策略可配。</p>
 *
 * <p>⚠️ 这里的正确性<b>不依赖"只跑一次"</b>：SPOP 本身原子，且业务侧是覆盖更新。
 * 所以路由策略用 FAILOVER 就够（不像 M3-2 的榜单结算必须分片广播才不重复）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikedTimesSyncJob {

    /** 支持点赞的业务类型。⚠️ 常见实现的 NOTE 只声明没实现，本阶段接通 */
    private static final List<String> BIZ_TYPES = List.of(MqConstants.BizType.QA, MqConstants.BizType.NOTE);

    private final ILikedRecordService likedRecordService;

    @Value("${remark.likes.sync-batch-size:30}")
    private int batchSize;

    @Value("${remark.likes.sync-max-rounds:10}")
    private int maxRounds;

    @XxlJob("likedTimesSyncJob")
    public void likedTimesSyncJob() {
        int total = 0;
        for (String bizType : BIZ_TYPES) {
            // 排空积压：一轮只处理 30 条，如果 20 秒内变更超过 30 条就会越积越多；
            // 连续跑到取不满一批为止（最多 maxRounds 轮，防止单次任务无限跑）
            for (int round = 0; round < maxRounds; round++) {
                int handled = likedRecordService.syncLikedTimes(bizType, batchSize);
                total += handled;
                if (handled < batchSize) {
                    break;
                }
            }
        }
        XxlJobHelper.log("点赞数同步完成：共同步 {} 条", total);
        log.info("[XXL-JOB] 点赞数同步完成：共同步 {} 条", total);
    }
}
