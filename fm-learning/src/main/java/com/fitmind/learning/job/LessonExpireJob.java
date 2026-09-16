package com.fitmind.learning.job;

import com.fitmind.learning.service.ILearningLessonService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 课表过期任务（XXL-JOB）。
 *
 * <p>为什么不自己写 @Scheduled：@Scheduled 在多实例下会重复执行（除非自己加分布式锁），
 * XXL-JOB 天然解决路由策略与失败重试，控制台可视。M3-2 的榜单月度结算还要用它的
 * <b>分片广播</b>能力，执行器在这里就一起接入。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LessonExpireJob {

    private final ILearningLessonService lessonService;

    /**
     * 每日 0 点：把 expire_time 已过的课表置为「已过期」。
     *
     * <p>已学进度保留——续费/重新报名后从断点继续（重新报名重置进度的逻辑在 enroll 里）。</p>
     */
    @XxlJob("lessonExpireJob")
    public void lessonExpireJob() {
        int n = lessonService.expireScan();
        XxlJobHelper.log("课表过期扫描完成：处理 {} 条", n);
        log.info("[XXL-JOB] 课表过期扫描完成：处理 {} 条", n);
    }
}
