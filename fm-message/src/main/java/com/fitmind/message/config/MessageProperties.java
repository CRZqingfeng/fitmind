package com.fitmind.message.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 消息模块配置（对应 yml 里的 {@code message.*}）。
 *
 * <p>用 {@code @ConfigurationProperties} 而不是逐个 {@code @Value}：
 * 这几个参数是<b>一组</b>概念（有效期、Mock 开关），放在一起既能被 IDE 提示，
 * 也方便将来整体调整。常见实现也是这个做法。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "message")
public class MessageProperties {

    /** 公告默认有效期（月）：公告是"一段时间内的通知"，过期就不再展开给用户 */
    private Integer noticeTtlMonths = 1;

    /** 定向通知默认有效期（月）：购课/退款这类业务通知要留得久些，便于用户回查 */
    private Integer messageTtlMonths = 6;

    /**
     * ★ 短信是否走 Mock。
     *
     * <p>Mock 不是"假装发短信"：它走的是与真实渠道<b>完全相同</b>的代码路径
     * （模板查找 → 内容渲染 → 调用 {@code ISmsHandler} → 落 sms_record），
     * 只是把"第三方"换成自己。</p>
     *
     * <p>这样做的收益和 M4-2 的 Mock 支付一致：<b>这条链路可以被自动化验收</b>，
     * 而不是"代码写了但从没跑过"。</p>
     */
    private Boolean smsMock = true;
}
