package com.fitmind.api.client.message.fallback;

import com.fitmind.api.client.message.MessageClient;
import com.fitmind.api.dto.message.NoticeSendDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

/**
 * {@link MessageClient} 的降级工厂。
 *
 * <p><b>★ 通知发失败不能影响主流程</b>，这一点比支付/退款要宽松得多：</p>
 * <ul>
 *   <li>支付失败 → 订单不能算成功（钱没到）</li>
 *   <li>退款失败 → 不能标成"已退款"（钱没退）</li>
 *   <li><b>通知失败 → 评分照样算完成</b>（学员少收一条站内信，
 *       但考核结果已经存库了，他自己去查也能看到）</li>
 * </ul>
 *
 * <p>所以这里的策略是<b>只记日志、吞掉异常</b>：
 * 绝不能让"消息服务挂了"变成"教练评不了分"。
 * 而支付/退款的 fallback 必须返回 null 让调用方感知 ——
 * 同样是降级，<b>语义不同，处理就不同</b>。</p>
 */
@Slf4j
@Component
@ConditionalOnClass(FallbackFactory.class)
public class MessageClientFallback implements FallbackFactory<MessageClient> {

    @Override
    public MessageClient create(Throwable cause) {
        return new MessageClient() {
            @Override
            public void sendByTemplate(NoticeSendDTO dto) {
                log.error("消息服务不可用，通知未发出（不影响主流程，结果已存库）。"
                                + "userId={}, template={}, sourceId={}，原因：{}",
                        dto == null ? null : dto.getUserId(),
                        dto == null ? null : dto.getTemplateCode(),
                        dto == null ? null : dto.getSourceId(),
                        cause.getMessage());
                // 故意不抛：见类注释
            }
        };
    }
}
