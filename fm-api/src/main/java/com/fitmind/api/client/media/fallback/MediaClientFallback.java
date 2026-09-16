package com.fitmind.api.client.media.fallback;

import com.fitmind.api.client.media.MediaClient;
import com.fitmind.api.dto.media.MediaDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * {@link MediaClient} 的降级工厂。
 *
 * <p>fm-media 不可用时返回 null 而不是抛异常，让调用方（课程模块）自行决定
 * 是降级处理还是提示用户，而不是让整条链路崩掉。</p>
 */
@Slf4j
@Component
@ConditionalOnClass(FallbackFactory.class)
public class MediaClientFallback implements FallbackFactory<MediaClient> {

    @Override
    public MediaClient create(Throwable cause) {
        return new MediaClient() {
            @Override
            public MediaDTO queryMediaById(Long id) {
                log.error("媒资服务不可用，无法查询媒资。id={}，原因：{}", id, cause.getMessage());
                return null;
            }
        };
    }
}
