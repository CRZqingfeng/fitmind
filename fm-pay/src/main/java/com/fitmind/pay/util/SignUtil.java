package com.fitmind.pay.util;

import cn.hutool.crypto.digest.DigestUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 回调签名工具。
 *
 * <p><b>为什么要验签</b>：回调接口是对公网的、不带我们的 JWT，
 * 任何人都能构造一个 {@code payOrderNo} 来调它。不验签就等于
 * <b>公开了一个"把任意订单改成已支付"的接口</b> —— 这是支付系统最典型的漏洞。</p>
 *
 * <p>这里用最直白的实现：参数按字典序拼接 + 密钥 → MD5。
 * 真实渠道各有各的签法（微信是 HMAC-SHA256、支付宝是 RSA），
 * 但<b>流程完全一样</b>：参数排序 → 拼串 → 加盐 → 哈希 → 比对。</p>
 */
@Component
public class SignUtil {

    @Value("${pay.sign-key:}")
    private String signKey;

    /** 生成签名 */
    public String sign(Map<String, String> params) {
        String raw = params.keySet().stream()
                .sorted()
                .map(k -> k + "=" + params.get(k))
                .collect(Collectors.joining("&"));
        return DigestUtil.md5Hex(raw + "&key=" + signKey).toUpperCase();
    }

    /** 校验签名（参数里已包含 sign 字段时先剔除它） */
    public boolean verify(Map<String, String> params, String sign) {
        if (sign == null || sign.isBlank()) {
            return false;
        }
        Map<String, String> copy = new TreeMap<>(params);
        copy.remove("sign");
        return sign(copy).equalsIgnoreCase(sign);
    }
}
