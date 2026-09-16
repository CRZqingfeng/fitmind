package com.fitmind.aigc.prompt;

import cn.hutool.core.util.StrUtil;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.fitmind.aigc.config.AigcProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Nacos 提示词来源（第一级）—— 支持不改代码、不重启就换提示词。
 *
 * <p><b>为什么要做这个</b>：调提示词是个高频操作（换个措辞、加一条约束、
 * 改一个输出格式），如果每次都要改代码 → 构建 → 重启，一轮几分钟，
 * 实际上根本没法好好调。放到配置中心就变成"改一下 → 立刻生效"。</p>
 *
 * <p>★ <b>与常见实现的关键差异：Nacos 是"可选增强"，不是"必要依赖"。</b>
 * 常见实现里 Nacos 读失败会让提示词变成空串，而这里读不到就返回 null、
 * 由上层回落到本地文件 —— <b>Nacos 挂了只是不能热更新，功能不受影响</b>。</p>
 *
 * <p>实现上刻意<b>不引入 {@code spring-cloud-starter-alibaba-nacos-config}</b>：
 * 那个 starter 会把 Nacos 变成"应用启动期必须连上"的配置源
 * （连不上可能直接启动失败），而我们只是想读几段文本。
 * 用 nacos-client 的原生 {@link ConfigService} API 更可控：
 * 初始化包在 try-catch 里，连不上就退化成本地模式。</p>
 */
@Slf4j
public class NacosPromptSource implements IPromptSource {

    private final AigcProperties.Prompt.Nacos props;

    /** 已确认存在的提示词 */
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    /** 每个 key 上次拉取的时间（TTL 判断用） */
    private final Map<String, Long> lastRefresh = new ConcurrentHashMap<>();
    /** 已注册监听的 dataId，避免重复注册（同一个 dataId 累积 listener 会重复回调） */
    private final Set<String> watching = ConcurrentHashMap.newKeySet();

    private ConfigService configService;

    public NacosPromptSource(AigcProperties.Prompt.Nacos props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        try {
            Properties properties = new Properties();
            properties.put(PropertyKeyConst.SERVER_ADDR, props.getServerAddr());
            properties.put(PropertyKeyConst.USERNAME, props.getUsername());
            properties.put(PropertyKeyConst.PASSWORD, props.getPassword());
            // ★ 命名空间显式设置，不依赖客户端默认值：
            //   客户端默认是 public，而"发布配置时的 namespace"很容易不是它 ——
            //   两者不一致时读不到任何配置，且**不报错**（详见 AigcProperties 里的说明）。
            //   显式写出来之后，这个值在配置里可见、可对齐，就不用去猜客户端默认是什么。
            if (StrUtil.isNotBlank(props.getNamespace())) {
                properties.put(PropertyKeyConst.NAMESPACE, props.getNamespace());
            }
            this.configService = NacosFactory.createConfigService(properties);
            log.info("★ Nacos 提示词源已启用：{}（namespace={}, group={}, dataId 前缀 {}）",
                    props.getServerAddr(), props.getNamespace(), props.getGroup(),
                    props.getDataIdPrefix());
        } catch (Exception e) {
            // ★ 连不上 Nacos 不是错误，只是"不能热更新"。
            //   这里绝不能往上抛 —— 否则一个可选的增强功能会把整个服务拖死。
            this.configService = null;
            log.warn("Nacos 提示词源不可用（将使用本地提示词，功能不受影响）：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void destroy() {
        if (configService != null) {
            try {
                configService.shutDown();
            } catch (Exception e) {
                log.debug("关闭 Nacos 配置服务异常（忽略）：{}", e.getMessage());
            }
        }
    }

    @Override
    public String load(String key) {
        if (configService == null || StrUtil.isBlank(key)) {
            return null;
        }
        // ★ 主动刷新：距上次拉取超过 refreshIntervalMs 就重新拉一次。
        //
        //   为什么已经有了 listener 还要这个 —— 这是本次实测的结论：
        //   在本环境（Nacos 服务端 2.3.2 + 客户端 3.0.3）里，
        //   `addListener` 注册是成功的（客户端日志有 `[add-listener] ok`），
        //   但**发布配置后没有收到推送**（客户端与服务端日志都没有 notify 记录）。
        //   推送机制依赖长连接状态、客户端与服务端版本组合、服务端推送配置，
        //   任一环节不匹配就会**静默地不生效** —— 没有报错、没有日志，
        //   只表现为"改了配置没反应"，排查成本很高。
        //
        //   所以这里改成"以主动拉取为主、listener 为加速"：
        //   · 拉取只依赖 getConfig（已被验证可用），可靠性高得多；
        //   · 开销可以忽略 —— getConfig 走客户端本地缓存 + 服务端比对 MD5，
        //     不传配置内容，30 秒一次；
        //   · listener 能工作时仍然是秒级生效（两者不冲突）。
        //
        //   对"改提示词"这个场景来说，30 秒生效完全够用了 ——
        //   这比"追求秒级推送但可能永远不生效"务实得多。
        Long last = lastRefresh.get(key);
        if (last != null && System.currentTimeMillis() - last < props.getRefreshIntervalMs()) {
            return cache.get(key);
        }
        return resolve(key);
    }

    private String resolve(String key) {
        String dataId = props.getDataIdPrefix() + key;
        String content;
        try {
            content = configService.getConfig(dataId, props.getGroup(), props.getTimeoutMs());
        } catch (Exception e) {
            // ⚠️ 读失败时**不**更新时间戳：Nacos 闪断恢复之后应该尽快重新读取，
            //    而不是"这一次没读到，等下一个 TTL 周期再说"
            log.warn("Nacos 提示词读取失败（本次回落到本地）：dataId={}，原因={}", dataId, e.getMessage());
            return null;
        }

        // 无论有没有配，都注册一次监听：这样运维"新建一个配置"也可能立刻生效
        watchOnce(key, dataId);

        if (StrUtil.isBlank(content)) {
            // ★ 用 debug 而不是 info：有了 TTL 刷新之后，这条日志每 30 秒就会出现一次，
            //   用 info 会把日志刷得没法看。真正需要注意的情况（读失败）仍然是 warn。
            log.debug("Nacos 上未配置该提示词，使用本地兜底：dataId={}", dataId);
            lastRefresh.put(key, System.currentTimeMillis());
            return null;
        }
        String text = content.trim();
        String previous = cache.put(key, text);
        lastRefresh.put(key, System.currentTimeMillis());
        if (!text.equals(previous)) {
            // 只在内容真的变了的时候打日志（首次加载、热更新、TTL 拉到新版本）
            log.info("★ Nacos 提示词已生效：dataId={}（{} 字）{}", dataId, text.length(),
                    previous == null ? "" : "[内容有变化]");
        }
        return text;
    }

    private void watchOnce(String key, String dataId) {
        if (!watching.add(dataId)) {
            return;
        }
        try {
            configService.addListener(dataId, props.getGroup(), new Listener() {
                @Override
                public Executor getExecutor() {
                    // 返回 null 表示用 Nacos 客户端的默认线程池
                    return null;
                }

                @Override
                public void receiveConfigInfo(String info) {
                    if (StrUtil.isBlank(info)) {
                        cache.remove(key);
                        log.info("★ 提示词被清空/删除，回落到本地兜底：dataId={}", dataId);
                    } else {
                        cache.put(key, info.trim());
                        log.info("★ 提示词已热更新：dataId={}（{} 字）", dataId, info.trim().length());
                    }
                }
            });
        } catch (Exception e) {
            // 监听注册失败不影响本次读取，只是"改配置要重启才生效"
            watching.remove(dataId);
            log.warn("注册提示词监听失败（改配置需重启生效）：dataId={}，原因={}", dataId, e.getMessage());
        }
    }

    /** 该 key 是否真的从 Nacos 拿到了内容（给 PromptManager 判断走的是哪一级） */
    public boolean has(String key) {
        return cache.containsKey(key);
    }

    @Override
    public String name() {
        return "nacos(" + props.getServerAddr() + ")";
    }
}
