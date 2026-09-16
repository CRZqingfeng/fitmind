package com.fitmind.api.client.remark.fallback;

import com.fitmind.api.client.remark.RemarkClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * {@link RemarkClient} 的降级工厂。
 *
 * <p>fm-remark 挂掉时返回<b>空集合</b>而不是 null：调用方（问答/笔记列表）
 * 只需要"这些内容我都没点过"，页面能正常渲染 —— 点赞状态是装饰性信息，
 * 不该因为它拖垮整个列表接口。</p>
 */
@Slf4j
@Component
@ConditionalOnClass(FallbackFactory.class)
public class RemarkClientFallback implements FallbackFactory<RemarkClient> {

    @Override
    public RemarkClient create(Throwable cause) {
        return new RemarkClient() {
            @Override
            public Set<Long> isBizLiked(String bizType, List<Long> bizIds) {
                log.error("点赞服务不可用，点赞状态降级为空。bizType={}，原因：{}", bizType, cause.getMessage());
                return Set.of();
            }
        };
    }
}
