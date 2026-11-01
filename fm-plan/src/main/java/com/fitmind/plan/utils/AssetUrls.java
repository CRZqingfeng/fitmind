package com.fitmind.plan.utils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 对象存储"对外基地址"的唯一出口：DB 里存对象 key，出参给绝对 URL。
 *
 * <p><b>本类最容易写错的地方：把 base-url 当成"上传用的配置"。</b>
 * 它<u>只</u>决定"浏览器从哪读"。上传用哪套 SDK 是 {@code fm.storage.type} 的事，
 * 两者刻意分开 —— 否则以后换存储后端时，你会以为改了一处其实只改了一半。</p>
 *
 * <p><b>为什么是静态持有，而不是每个 Service 注入：</b>
 * 需要拼这个地址的地方有 4 处（动作库列表 / 动作详情 / 计划动作行 / 训练执行页），
 * 其中 {@link PlanVoAssembler} 是<u>静态工具类</u>。注入就得把 base-url 当参数一路穿到
 * 4 条调用链上，<b>漏一个的表现是"某一个列表的图全裂"</b> —— 而原因藏在方法签名里，
 * 页面上完全看不出来。静态持有 = 只有一个入口，且只在启动时写一次。
 * （同类先例：{@code UserContext} 也是静态持有。）</p>
 *
 * <p><b>为什么兼容 http 开头：</b>库里可能同时存在"老的外链绝对地址"和"新导入的对象 key"。
 * 已经是 http(s) 就原样返回，于是<b>不用一次性刷全表</b>也能两种行共存。</p>
 *
 * <p>★ 启动时会打印一行 {@code fm.asset.base-url = ...}，理由与
 * "对象存储排查先看启动横幅 {@code bucket : …}" 是同一条判据：
 * 图不显示时，第一个要确认的就是"后端拼出来的地址对不对"。</p>
 */
public final class AssetUrls {

    /** 对外基地址（不带结尾斜杠），例如 {@code http://localhost:9000/fitmind} */
    private static volatile String baseUrl = "";

    private AssetUrls() {
    }

    /** 由 {@link Config} 在启动时调用一次 */
    static void configure(String url) {
        String v = url == null ? "" : url.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        baseUrl = v;
    }

    public static String baseUrl() {
        return baseUrl;
    }

    /**
     * 对象 key → 绝对 URL；已经是 http(s) 的地址原样返回；null 返回 null。
     *
     * <p>★ 空 base-url（没配）时**返回原值**而不是拼出 {@code /action-cover/...}：
     * 拼出半截地址会让前端把相对路径当成本站路径去请求（404），
     * 而"原样返回"至少还能看出是个没配的 key。</p>
     */
    public static String toUrl(String keyOrUrl) {
        if (keyOrUrl == null || keyOrUrl.isEmpty()) {
            return keyOrUrl;
        }
        if (keyOrUrl.startsWith("http://") || keyOrUrl.startsWith("https://")) {
            return keyOrUrl;
        }
        if (baseUrl.isEmpty()) {
            return keyOrUrl;
        }
        return baseUrl + "/" + (keyOrUrl.startsWith("/") ? keyOrUrl.substring(1) : keyOrUrl);
    }

    /**
     * 启动时把配置灌进静态字段，并打印一行。
     *
     * <p>★ 用 {@code @Value} 注入再 {@code @PostConstruct} 写静态字段，
     * 而不是 {@code @ConfigurationProperties}：本类只有一个字段，不值得为它建一个属性类。</p>
     */
    @Component
    public static class Config {

        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AssetUrls.class);

        @Value("${fm.asset.base-url:}")
        private String baseUrl;

        @PostConstruct
        public void init() {
            AssetUrls.configure(baseUrl);
            log.info("fm.asset.base-url = {}（动作/食物图片：DB 存对象 key，这里拼成浏览器可读的绝对地址）",
                    AssetUrls.baseUrl().isEmpty() ? "(未配置 → 原样返回 key，图片将不可用)" : AssetUrls.baseUrl());
        }
    }
}
