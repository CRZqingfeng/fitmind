package com.fitmind.pay.controller;

import com.fitmind.pay.constants.PayConstants;
import com.fitmind.pay.domain.dto.PayNotifyDTO;
import com.fitmind.pay.domain.po.PayOrder;
import com.fitmind.pay.service.IPayOrderService;
import com.fitmind.pay.util.SignUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * ★ 模拟支付（只在本项目存在，常见实现没有）。
 *
 * <p>它做的事：代替"第三方服务器"，<b>调用我们自己的回调接口</b>
 * （{@code POST /ps/notify/mockPay}）。也就是说 ——
 * 从验签、幂等、MQ 到订单更新、加课表，<b>走的全部是真实代码路径</b>，
 * 只是触发方从微信服务器变成了这里。</p>
 *
 * <p>为什么不让 {@code /mock/pay} 直接改数据库：
 * 那样"支付成功"就绕过了回调链路，真正容易出错的那段（验签、幂等、发消息）
 * 反而<b>测不到</b>。常见实现的 7 个坑里有 3 个恰好就在那段上。</p>
 *
 * <p>★★ <b>只在 {@code pay.mock.enabled=true} 时注册</b>（缺省即关闭，P0-1）：</p>
 * <ul>
 *   <li>这个控制器能让任意订单"变成已支付"，{@code /sign/{no}} 还能签发<b>合法签名</b>
 *       —— 暴露在任何有公网入口的环境上就是零元购；</li>
 *   <li>所以按"<b>忘配 = 不存在</b>"设计，而不是"忘配 = 裸奔"。
 *       新增环境忘了抄配置时，结果是 404，而不是漏洞；</li>
 *   <li>本地闭环演示：在 local profile 的 application.yml 里显式写
 *       {@code pay.mock.enabled: true}。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/mock")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pay.mock", name = "enabled", havingValue = "true")
@Tag(name = "模拟支付（本地闭环用）")
public class MockPayController {

    private final IPayOrderService payOrderService;
    private final SignUtil signUtil;

    /**
     * 模拟"用户在收银台点了【支付成功】"。
     *
     * <p>内部用 HTTP 调用自己的 {@code /ps/notify/mockPay}，
     * 与真实第三方回调走完全相同的代码。</p>
     */
    @Operation(summary = "模拟支付成功（触发真实回调链路）")
    @Parameters(@Parameter(name = "payOrderNo", description = "支付单号"))
    @PostMapping("/pay/{payOrderNo}")
    public String mockPay(@PathVariable("payOrderNo") String payOrderNo) {
        return doNotify(payOrderNo, "SUCCESS");
    }

    /** 模拟支付失败（订单保持待支付，支付单关闭） */
    @Operation(summary = "模拟支付失败")
    @Parameters(@Parameter(name = "payOrderNo", description = "支付单号"))
    @PostMapping("/fail/{payOrderNo}")
    public String mockFail(@PathVariable("payOrderNo") String payOrderNo) {
        return doNotify(payOrderNo, "FAIL");
    }

    /**
     * 供脚本 / 前端生成合法签名（否则手工调 notify 接口会被验签挡住）。
     *
     * <p>真实场景里签名是渠道方算的，我们永远不会提供这种"帮你算签名"的接口。
     * 它只存在于 Mock 渠道，且和支付能力无关 —— 拿到签名也改不了订单状态
     * （还要过条件更新那道闸）。</p>
     */
    @Operation(summary = "生成回调签名（仅 Mock 渠道用）")
    @Parameters(@Parameter(name = "payOrderNo", description = "支付单号"))
    @GetMapping("/sign/{payOrderNo}")
    public String sign(@PathVariable("payOrderNo") String payOrderNo) {
        Map<String, String> params = new HashMap<>();
        params.put("payOrderNo", payOrderNo);
        params.put("result", "SUCCESS");
        return signUtil.sign(params);
    }

    /** 模拟收银台页面（二维码指向它） */
    @Operation(summary = "模拟收银台页面")
    @GetMapping(value = "/cashier/{payOrderNo}", produces = MediaType.TEXT_HTML_VALUE)
    public String cashier(@PathVariable("payOrderNo") String payOrderNo) {
        PayOrder po = payOrderService.getByPayOrderNo(payOrderNo);
        String desc = po == null ? "支付单不存在" : ("金额 " + po.getAmount() + " 分，状态 " + po.getStatus());
        return "<html><body style='font-family:sans-serif;padding:40px'>"
                + "<h2>FitMind 模拟收银台</h2>"
                + "<p><b>" + payOrderNo + "</b></p><p>" + desc + "</p>"
                + "<p><a href='/ps/mock/pay/" + payOrderNo + "'>（真实场景：用户扫码付款）</a></p>"
                + "</body></html>";
    }

    /**
     * 内部：构造回调参数并<b>真的发起一次 HTTP 回调</b>。
     *
     * <p>用 RestTemplate 调自己，而不是直接调 Service ——
     * 这样连"网关放行 /notify、验签、参数绑定"这些环节都被覆盖到。</p>
     */
    private String doNotify(String payOrderNo, String result) {
        Map<String, String> params = new HashMap<>();
        params.put("payOrderNo", payOrderNo);
        params.put("result", result);
        String sign = signUtil.sign(params);

        PayNotifyDTO dto = new PayNotifyDTO();
        dto.setPayOrderNo(payOrderNo);
        dto.setResult(result);
        dto.setChannelTradeNo("MOCK" + System.currentTimeMillis());
        dto.setSign(sign);

        // 直接回调本服务的 notify 接口（与第三方调我们的路径一致）
        new org.springframework.web.client.RestTemplate().postForObject(
                "http://127.0.0.1:8089/notify/" + PayConstants.MOCK_CHANNEL_CODE, dto, String.class);
        log.info("[Mock] 已触发回调：payOrderNo={}, result={}", payOrderNo, result);
        return "notified:" + result;
    }
}
