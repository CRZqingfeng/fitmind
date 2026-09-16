package com.fitmind.api.client.remark;

import com.fitmind.api.client.remark.fallback.RemarkClientFallback;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

/**
 * 点赞服务 Feign 客户端。
 *
 * <p>业务方（fm-learning）在列表页用它批量查「当前用户点过哪些」——
 * 点赞状态是<b>按人</b>的，没法像点赞数那样冗余到业务表上，只能实时问。</p>
 *
 * <p>⚠️ 必须带 {@code bizType}：fm-remark 的 Redis key 是
 * {@code like:set:{bizType}:{bizId}}。常见实现只用 bizId 做 key（依赖雪花 id 全局唯一），
 * 我们的主键是自增的，不带类型会串数据。</p>
 */
@FeignClient(value = "fm-remark", fallbackFactory = RemarkClientFallback.class)
public interface RemarkClient {

    /**
     * 批量查询当前用户的点赞状态。
     *
     * @param bizType 业务类型：QA-问答回答 NOTE-笔记
     * @param bizIds  业务id 集合
     * @return 已点赞的业务id 子集
     */
    @GetMapping("/remarks/likes/list")
    Set<Long> isBizLiked(@RequestParam("bizType") String bizType,
                         @RequestParam("bizIds") List<Long> bizIds);
}
