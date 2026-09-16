package com.fitmind.message.thirdparty;

import com.fitmind.message.domain.po.SmsRecord;

/**
 * 短信渠道统一接口（防腐层）。
 *
 * <p><b>★ 为什么要有这个接口，而不是直接调 SDK</b>：
 * 常见实现接了阿里 / 腾讯 / UC 三家短信平台，靠 {@code Map<String, ISmsHandler>}
 * 按优先级逐个尝试。这层抽象的价值不在"多平台容灾"，而在
 * <b>把"短信怎么发出去"这件事和"什么时候该发短信"这件事分开</b> ——
 * 前者是外部渠道的方言，后者才是业务。</p>
 *
 * <p>我们本地只实现 Mock 渠道，但接口保留：将来接真实渠道时
 * 只需新增一个实现类，业务代码（渲染 → 调用 → 留痕）一行都不用改。</p>
 *
 * <p>⚠️ <b>实现方不负责落库</b>：只把结果（成功/失败、失败原因）回填到
 * {@link SmsRecord} 上。落库由调用方统一做 —— 这样"短信没发出去"
 * 这件事本身也会留下记录（如果让实现方落库，失败路径很容易忘了写）。</p>
 */
public interface ISmsHandler {

    /**
     * 下发一条短信。
     *
     * @param record 短信内容与元信息；实现方把下发结果回填到
     *               {@code status} / {@code resultMsg} 上
     */
    void send(SmsRecord record);

    /** 渠道代号（用于日志与配置，如 {@code mockSms}） */
    String channelCode();
}
