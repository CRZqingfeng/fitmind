package com.fitmind.pay.controller;

import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.pay.constants.PayErrorInfo;
import com.fitmind.pay.domain.dto.RefundNotifyDTO;
import com.fitmind.pay.service.IRefundOrderService;
import com.fitmind.pay.util.SignUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 退款结果回调。
 *
 * <p>与支付回调（{@code NotifyController}）同构，三道闸门一个不少：</p>
 * <ol>
 *   <li><b>验签</b>：不验签等于公开一个"把任意退款单改成成功"的接口</li>
 *   <li><b>状态条件更新</b>：只有"退款中 → 成功/失败"成功才继续（在 Service 里）</li>
 *   <li><b>成功后才发 MQ</b>：重复回调不会重复通知交易模块</li>
 * </ol>
 *
 * <p>⚠️ <b>这个接口必须在网关的登录白名单里</b>（{@code /ps/notify/**}）——
 * 第三方服务器不会带我们的 JWT，安全完全由验签保证。
 * 支付回调已经放行过同一前缀，退款走同一个前缀所以不需要改网关配置。</p>
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/notify/refund")
@RequiredArgsConstructor
@Tag(name = "退款结果回调（第三方 / Mock 共用）")
public class RefundNotifyController {

    private final IRefundOrderService refundOrderService;
    private final SignUtil signUtil;

    @Operation(summary = "退款结果回调（需验签）")
    @PostMapping("/{channelCode}")
    public String notify(@PathVariable("channelCode") String channelCode,
                         @RequestBody RefundNotifyDTO dto) {
        if (dto == null || dto.getRefundOrderNo() == null || dto.getResult() == null) {
            throw new BizIllegalException(PayErrorInfo.Msg.NOTIFY_PARAM_ILLEGAL);
        }
        // 1. 验签（Mock 渠道也走这一步 —— 让验签逻辑本身被真实覆盖）
        //    ⚠️ 参与签名的字段必须与 MockRefundController.doNotify 的 sign() 严格一致
        Map<String, String> params = new HashMap<>();
        params.put("refundOrderNo", dto.getRefundOrderNo());
        params.put("result", dto.getResult());
        if (!signUtil.verify(params, dto.getSign())) {
            log.warn("退款回调验签失败：channelCode={}, refundOrderNo={}",
                    channelCode, dto.getRefundOrderNo());
            throw new BizIllegalException(PayErrorInfo.Msg.NOTIFY_SIGN_INVALID);
        }
        // 2. 结果解析：只认 SUCCESS / FAIL，其它一律拒绝
        //    （不做"非 SUCCESS 就当失败"的宽松处理 —— 那样一个拼错的 result
        //      会把在途的退款直接标成失败，用户看到的就是"退款失败"）
        boolean success;
        if ("SUCCESS".equalsIgnoreCase(dto.getResult())) {
            success = true;
        } else if ("FAIL".equalsIgnoreCase(dto.getResult())) {
            success = false;
        } else {
            throw new BizIllegalException(PayErrorInfo.Msg.REFUND_NOTIFY_RESULT_INVALID);
        }
        // 3. 处理（内部用条件更新做幂等，重复回调返回 false）
        boolean first = refundOrderService.handleRefundNotify(dto.getRefundOrderNo(), success,
                success ? null : "渠道返回退款失败");
        log.info("退款回调处理完毕：refundOrderNo={}, result={}, 是否首次={}",
                dto.getRefundOrderNo(), dto.getResult(), first);
        return "success";
    }
}
