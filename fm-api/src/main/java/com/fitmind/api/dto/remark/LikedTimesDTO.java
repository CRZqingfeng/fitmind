package com.fitmind.api.dto.remark;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 点赞数变更（fm-remark → 业务方）。
 *
 * <p>一条消息里会带<b>一批</b>（默认 30 条）变更，业务方用 {@code updateBatchById}
 * 一次刷完，而不是每条变更发一条消息。</p>
 *
 * <p>⚠️ 业务方是<b>覆盖更新</b>（{@code set liked_times = N}），不是累加 ——
 * 所以这条消息重复投递是无害的，不需要像积分那样做唯一索引幂等。
 * 这是本阶段最值得讲的一条设计对照。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor(staticName = "of")
@Schema(description = "点赞数变更")
public class LikedTimesDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "业务id（回答id / 笔记id）")
    private Long bizId;

    @Schema(description = "最新点赞数")
    private Integer likedTimes;
}
