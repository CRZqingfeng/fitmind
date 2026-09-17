package com.fitmind.aigc.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 课程检索（fm-search）—— AI 工具专用客户端。
 *
 * <p>⚠️ <b>为什么这个客户端放在 fm-aigc 而不是 fm-api</b>（这是对项目既有约定的一个有意识偏离）：</p>
 *
 * <p>{@code fm-api} 是「服务间<b>领域契约</b>」模块，里面 7 个客户端
 * （user / course / media / trade 相关 / remark / coupon / message）
 * 都是<b>多个服务共用</b>的。而本模块需要的这两个客户端
 * <b>只服务于 AI 工具调用</b> —— 服务方（fm-search / fm-trade）
 * 根本不需要知道"有个 AI 在调它们"，也不会去用这些接口的 AI 语义。</p>
 *
 * <p>放进 fm-api 的代价是把「AI 能力」的依赖传染给 15 个服务，
 * 而收益只有"位置统一"。放在这里则边界清晰：
 * <b>谁用谁定义，用不到的人一行都不用改</b>。</p>
 *
 * <p>★ <b>唯一必须守住的那条线</b>：调用方<b>必须像普通前端一样被鉴权</b>。
 * 这一点由 SDK 的 {@code FeignRelayUserInterceptor} 保证 ——
 * 它会把当前请求的 {@code user-info} 与 token 透传下去，
 * 于是被调服务看到的仍然是"用户在调"，而不是"AI 服务在调"。
 * <b>如果这里绕过了鉴权，那 AI 就成了一个越权通道。</b></p>
 */
@FeignClient(value = "fm-search", path = "/courses")
public interface SearchClient {

    /**
     * 按关键词查课程 id（fm-search 已有接口，走的是 ES 全文检索）。
     *
     * <p>★ 用它而不是新加一个"AI 专用搜索接口"：课程检索这件事 fm-search 已经做好了
     * （IK 分词、相关性排序、索引同步），AI 再去查一遍业务库属于重复建设。
     * <b>"复用已有服务"比"为 AI 单独造一套"更省事，也更不容易出现两份口径不一致。</b></p>
     */
    @GetMapping("/name")
    List<Long> queryIdsByKeyword(@RequestParam("keyword") String keyword, @RequestParam("size") int size);
}
