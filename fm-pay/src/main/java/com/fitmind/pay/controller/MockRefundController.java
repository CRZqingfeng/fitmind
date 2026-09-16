package com.fitmind.pay.controller;

import com.fitmind.pay.constants.PayConstants;
import com.fitmind.pay.domain.dto.RefundNotifyDTO;
import com.fitmind.pay.service.IRefundOrderService;
import com.fitmind.pay.util.SignUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * ★ 模拟退款（只在本项目存在）。
 *
 * <p>与 {@code MockPayController} 完全对称 —— 那是"扮演第三方通知我们支付成功"，
 * 这里是"扮演第三方通知我们退款成功"。</p>
 *
 * <p><b>它存在的意义</b>：真实渠道的退款是异步的，所以"退款在途 → 渠道通知 → 结清"
 * 这段代码<b>必须被真的跑过</b>。靠真实渠道验证是不可能的（本地没有商户号），
 * 直接改数据库又绕过了验签、条件更新、MQ 这些真正容易错的环节。
 * 让 Mock 渠道走一次真实回调，这段链路就<b>可自动化验收</b>了。</p>
 *
 * <p>⚠️ 与支付侧对齐的一点：<b>签名参数必须与 {@code /notify/refund} 侧严格一致</b>
 * （这里是 {@code refundOrderNo} + {@code result}）。M4-2 在支付回调上踩过一次
 * "两边字段不一致导致永远验不过"的坑，所以两侧都写了这句注释互相提醒。</p>
 */
@Slf4j
@RestController
@RequestMapping("/mock/refund")
@RequiredArgsConstructor
@Tag(name = "模拟退款（本地闭环用）")
public class MockRefundController {

    private final IRefundOrderService refundOrderService;
    private final SignUtil signUtil;

    /**
     * 模拟"渠道处理完了，退款成功"。
     *
     * <p>内部用 HTTP 调用自己的 {@code /ps/notify/refund/mockPay}，
     * 与真实第三方回调走<b>完全相同的代码</b>（验签 → 条件更新 → 发 MQ）。</p>
     */
    @Operation(summary = "模拟退款成功（触发真实回调链路）")
    @Parameters(@Parameter(name = "refundOrderNo", description = "退款单号"))
    @PostMapping("/{refundOrderNo}")
    public String mockRefund(@PathVariable("refundOrderNo") String refundOrderNo) {
        return doNotify(refundOrderNo, "SUCCESS");
    }

    /** 模拟"渠道退款失败"（例如渠道账户余额不足） */
    @Operation(summary = "模拟退款失败")
    @Parameters(@Parameter(name = "refundOrderNo", description = "退款单号"))
    @PostMapping("/fail/{refundOrderNo}")
    public String mockRefundFail(@PathVariable("refundOrderNo") String refundOrderNo) {
        return doNotify(refundOrderNo, "FAIL");
    }

    /**
     * 生成合法签名（供脚本调试；真实渠道永远不会提供这种接口）。
     *
     * <p>与支付侧同理：拿到签名也改不了退款状态 —— 还要过条件更新那道闸。</p>
     */
    @Operation(summary = "生成退款回调签名（仅 Mock 渠道用）")
    @Parameters(@Parameter(name = "refundOrderNo", description = "退款单号"))
    @GetMapping("/sign/{refundOrderNo}")
    public String sign(@PathVariable("refundOrderNo") String refundOrderNo) {
        Map<String, String> params = new HashMap<>();
        params.put("refundOrderNo", refundOrderNo);
        params.put("result", "SUCCESS");
        return signUtil.sign(params);
    }

    /**
     * 内部：构造回调参数并<b>真的发起一次 HTTP 回调</b>。
     *
     * <p>用 RestTemplate 调自己而不是直接调 Service —— 这样连
     * "网关放行 /notify/**、验签、参数绑定" 这些环节都被覆盖到。</p>
     */
    private String doNotify(String refundOrderNo, String result) {
        Map<String, String> params = new HashMap<>();
        params.put("refundOrderNo", refundOrderNo);
        params.put("result", result);
        String sign = signUtil.sign(params);

        RefundNotifyDTO dto = new RefundNotifyDTO();
        dto.setRefundOrderNo(refundOrderNo);
        dto.setResult(result);
        dto.setChannelRefundNo("MOCKRFD" + System.currentTimeMillis());
        dto.setSign(sign);

        new org.springframework.web.client.RestTemplate().postForObject(
                "http://127.0.0.1:8089/notify/refund/" + PayConstants.MOCK_CHANNEL_CODE,
                dto, String.class);
        log.info("[Mock退款] 已触发回调：refundOrderNo={}, result={}", refundOrderNo, result);
        return "notified:" + result;
    }
}
