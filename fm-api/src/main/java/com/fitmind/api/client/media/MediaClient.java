package com.fitmind.api.client.media;

import com.fitmind.api.client.media.fallback.MediaClientFallback;
import com.fitmind.api.dto.media.MediaDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 媒资服务 Feign 客户端。
 *
 * <p>供 fm-course 在保存课程目录时校验 videoId 是否真实存在、并取回时长。</p>
 *
 * <p>返回类型是领域对象而非 {@code R}——服务间调用不带
 * {@code request-from: gateway} 头，不会被包装。</p>
 */
@FeignClient(value = "fm-media", fallbackFactory = MediaClientFallback.class)
public interface MediaClient {

    /**
     * 按 id 查询媒资。
     *
     * @param id 媒资id
     * @return 媒资信息；不存在时返回 null
     */
    @GetMapping("/medias/internal/{id}")
    MediaDTO queryMediaById(@PathVariable("id") Long id);
}
