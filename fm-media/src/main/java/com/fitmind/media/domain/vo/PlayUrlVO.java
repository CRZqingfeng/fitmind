package com.fitmind.media.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 播放地址。
 *
 * <p>bucket 是私有的，播放地址必须带签名、且会过期，
 * 所以不能把地址硬编码在前端或数据库里，每次播放前来换一个。</p>
 */
@Data
@Schema(description = "播放地址")
public class PlayUrlVO {

    @Schema(description = "带签名的播放地址")
    private String url;

    @Schema(description = "有效期，单位秒")
    private int expire;

    @Schema(description = "时长，单位秒")
    private Integer duration;

    public static PlayUrlVO of(String url, int expire, Integer duration) {
        PlayUrlVO vo = new PlayUrlVO();
        vo.setUrl(url);
        vo.setExpire(expire);
        vo.setDuration(duration);
        return vo;
    }
}
