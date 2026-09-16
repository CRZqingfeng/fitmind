package com.fitmind.message.thirdparty;

import com.fitmind.message.constants.MessageConstants;
import com.fitmind.message.domain.po.SmsRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ★ Mock 短信渠道（本地闭环）。
 *
 * <p><b>它"模拟"了什么，没模拟什么</b>：</p>
 * <ul>
 *   <li><b>没模拟</b>：模板查找、内容渲染、渠道调用、结果回填、落库留痕 ——
 *       这些全部是真实代码，一条不少</li>
 *   <li><b>模拟了</b>：只有最外层那一次"向第三方服务器发 HTTP 请求"</li>
 * </ul>
 *
 * <p>所以这条链路的正确性是可以被验证的，只是"第三方"换成了自己。
 * 这与 M4-2 的 Mock 支付、M4-4 的 Mock 退款是同一个思路 ——
 * <b>整条链路可自动化验收，代价是简历上写不了"对接阿里云短信"</b>。</p>
 *
 * <p>Mock 下发的表现：① 打一条 WARN 日志（便于肉眼观察）；
 * ② 结果回填为成功，调用方落进 {@code sms_record} 表（验收脚本直查）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockSmsHandler implements ISmsHandler {

    @Override
    public void send(SmsRecord record) {
        // 真实渠道这里会：签名校验 → 拼 SDK 参数 → HTTP 调用 → 解析返回码
        // Mock 只把结果置为成功。注意失败分支没被删掉 ——
        // 调用方依然按"成功/失败"两条路处理，只是这里不会走失败分支
        record.setStatus(MessageConstants.SMS_SUCCESS)
                .setResultMsg("Mock 渠道下发成功");
        log.warn("【Mock 短信】to={}, template={}, content={}",
                record.getPhone(), record.getTemplateCode(), record.getContent());
    }

    @Override
    public String channelCode() {
        return "mockSms";
    }
}
