package com.fitmind.pay.controller;

import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.pay.constants.PayErrorInfo;
import com.fitmind.pay.domain.dto.PayNotifyDTO;
import com.fitmind.pay.service.IPayOrderService;
import com.fitmind.pay.util.SignUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 支付结果回调。
 *
 * <p>⚠️ 这个接口<b>在网关的登录白名单里</b>（{@code /ps/notify/**}）——
 * 第三方服务器不会带我们的 JWT。它的安全完全由<b>验签</b>保证。</p>
 *
 * <p><b>★ 三道闸门</b>：</p>
 * <ol>
 *   <li><b>验签</b>：确认请求确实来自支付渠道（否则等于公开一个"把订单改成已支付"的接口）</li>
 *   <li><b>状态条件更新</b>：只有"待支付 → 已支付"成功才继续，重复回调直接忽略</li>
 *   <li><b>成功后才发 MQ</b>：重复回调不会重复通知交易模块</li>
 * </ol>
 *
 * <p>常见实现在这里有 3 个坑，我们都避开了：
 * ① {@code queryPayResult} 里 {@code status != SUCCESS} 分支<b>缺 return</b>，
 *    导致"支付中"的订单也被标记成已支付；
 * ② 延迟轮询成功分支未回填 {@code bizOrderId}；
 * ③ 消费端没有幂等。
 * 我们的做法是把"状态变更"和"发消息"绑在同一个条件更新的结果上 ——
 * 改成功了才发，天然不会重复。</p>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/notify")
@RequiredArgsConstructor
@Tag(name = "支付结果回调（第三方 / Mock 共用）")
public class NotifyController {

    private final IPayOrderService payOrderService;
    private final SignUtil signUtil;

    @Operation(summary = "支付结果回调（需验签）")
    @PostMapping("/{channelCode}")
    public String notify(@PathVariable("channelCode") String channelCode,
                         @RequestBody PayNotifyDTO dto) {
        if (dto == null || dto.getPayOrderNo() == null || dto.getResult() == null) {
            throw new BizIllegalException(PayErrorInfo.Msg.NOTIFY_PARAM_ILLEGAL);
        }
        // 1. 验签（Mock 渠道也走这一步 —— 这样验签逻辑本身也被真实测试覆盖）
        //
        //    ⚠️ 参与签名的字段必须与渠道侧严格一致，否则永远验不过。
        //    这里约定只有 payOrderNo 与 result 参与（channelTradeNo 只是对账用的
        //    渠道流水号，不参与签名）—— 与 MockPayController 的 sign() 完全对齐。
        Map<String, String> params = new HashMap<>();
        params.put("payOrderNo", dto.getPayOrderNo());
        params.put("result", dto.getResult());
        if (!signUtil.verify(params, dto.getSign())) {
            log.warn("回调验签失败：channelCode={}, payOrderNo={}", channelCode, dto.getPayOrderNo());
            throw new BizIllegalException(PayErrorInfo.Msg.NOTIFY_SIGN_INVALID);
        }

        // 2. 按结果处理（成功 / 失败）
        if ("SUCCESS".equalsIgnoreCase(dto.getResult())) {
            // ★ handlePaySuccess 内部用条件更新做幂等，重复回调返回 false
            boolean first = payOrderService.handlePaySuccess(dto.getPayOrderNo());
            log.info("支付成功回调处理完毕：payOrderNo={}, 是否首次={}", dto.getPayOrderNo(), first);
        } else {
            payOrderService.closePayOrder(dto.getPayOrderNo());
            log.info("支付失败回调：payOrderNo={}", dto.getPayOrderNo());
        }
        // 真实渠道要求返回特定报文（如微信的 <return_code>SUCCESS</return_code>）
        return "success";
    }
}
