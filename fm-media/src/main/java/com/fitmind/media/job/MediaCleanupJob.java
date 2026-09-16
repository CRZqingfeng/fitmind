package com.fitmind.media.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fitmind.media.domain.po.Media;
import com.fitmind.media.enums.FileStatus;
import com.fitmind.media.service.IMediaService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 僵尸媒资清理任务（XXL-JOB）—— 还 M2-1 的欠账。
 *
 * <p><b>什么是僵尸记录</b>：前端申请了预签名 URL（预创建 status=1），但始终没来 confirm——
 * 拿了地址就关页面、上传失败放弃，都会留下这种记录。它们在 MinIO 里
 * 可能根本没对象，也可能有半截文件，占着库和存储。</p>
 *
 * <p><b>清理规则</b>：status=1（上传中）超过 24 小时 → 删 MinIO 对象（若有）+ 删记录。
 * 24 小时是给慢网络的余量；confirm 之后就变成 status=2，不会误伤。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaCleanupJob {

    /** 上传中状态保留的最长时间（小时） */
    private static final int MAX_UPLOADING_HOURS = 24;

    private final IMediaService mediaService;

    @XxlJob("mediaCleanupJob")
    public void mediaCleanupJob() {
        LocalDateTime deadline = LocalDateTime.now().minusHours(MAX_UPLOADING_HOURS);
        List<Media> zombies = mediaService.list(new LambdaQueryWrapper<Media>()
                .eq(Media::getStatus, FileStatus.UPLOADING)
                .lt(Media::getCreateTime, deadline));
        if (zombies.isEmpty()) {
            XxlJobHelper.log("无僵尸媒资需要清理");
            return;
        }
        int ok = 0;
        for (Media media : zombies) {
            try {
                // deleteMedia 内部会先删 MinIO 对象再删记录（M2-1 的逻辑，幂等）
                mediaService.deleteMedia(media.getId());
                ok++;
            } catch (Exception e) {
                // 单条失败不阻断批量，但要留痕
                log.error("清理僵尸媒资失败：id={}, fileId={}，原因：{}",
                        media.getId(), media.getFileId(), e.getMessage());
                XxlJobHelper.log("清理失败：id={}, 原因：{}", media.getId(), e.getMessage());
            }
        }
        XxlJobHelper.log("僵尸媒资清理完成：发现 {} 条，清理成功 {} 条", zombies.size(), ok);
        log.info("[XXL-JOB] 僵尸媒资清理完成：发现 {} 条，清理成功 {} 条", zombies.size(), ok);
    }
}
