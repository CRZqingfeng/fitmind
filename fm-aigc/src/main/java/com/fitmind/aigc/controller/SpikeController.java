package com.fitmind.aigc.controller;

import com.fitmind.api.client.course.CourseClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ M6-0 spike 专用控制器：验证「只给 fm-aigc 升版本」这条路的 5 项连通性。
 *
 * <p>这是一个**一次性的可行性探针**，不是正式接口。spike 定版后会删掉它，
 * 换成真正的 {@code ChatController}（SSE 流式）。</p>
 *
 * <p>它一次验证 5 件事（每项失败都能从返回体直接看出来）：</p>
 * <ol>
 *   <li><b>服务能启动</b> —— 能返回就说明 Boot 3.5 + Spring Cloud 2025 + Alibaba 2025
 *       的自动装配没炸（这一步过不了，下面都谈不上）</li>
 *   <li><b>Nacos 注册成功</b> —— 返回本机 IP + 端口；注册失败时网关会 503</li>
 *   <li><b>能被网关路由</b> —— 通过 {@code /ais/spike/ping} 访问（走网关而非直连）</li>
 *   <li><b>Feign 能调到 fm-course</b> —— 共享模块 fm-common / fm-api 是用 Boot 3.3.5
 *       编译的，跑在 Boot 3.5 上是否兼容，只有真调一次才知道</li>
 *   <li><b>Spring AI 自动装配 + 真实模型调用</b> —— 注入的 {@code ChatClient.Builder}
 *       来自 Spring AI 1.1.8 的自动配置，能真的问出一个答案才算过</li>
 * </ol>
 */
@Slf4j
@Tag(name = "M6-0 spike 探针（定版后删除）")
@RestController
@RequestMapping("/spike")
@RequiredArgsConstructor
public class SpikeController {

    /** ★ 来自 Spring AI 自动装配 —— 注入成功本身就说明自动配置生效了 */
    private final ChatClient.Builder chatClientBuilder;

    /** ★ Feign 客户端（fm-api 里的契约），验证共享模块在 Boot 3.5 上的运行期兼容 */
    private final CourseClient courseClient;

    @Value("${server.port:8094}")
    private int port;

    @Value("${spring.application.name:unknown}")
    private String appName;

    @Value("${aigc.spike.spring-ai-version:unknown}")
    private String springAiVersion;

    @Operation(summary = "① 服务存活 + ② 版本信息")
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("app", appName);
        r.put("port", port);
        r.put("status", "UP");
        // 运行期真实的 Spring Boot / Spring AI 版本（不是 pom 里写的）
        r.put("springBoot", org.springframework.boot.SpringBootVersion.getVersion());
        r.put("springAiConfigured", springAiVersion);
        r.put("java", System.getProperty("java.version"));
        log.info("spike ping：{}", r);
        return r;
    }

    @Operation(summary = "④ Feign 调 fm-course（验证共享模块跨 Boot 版本的运行期兼容）")
    @GetMapping("/feign")
    public Map<String, Object> feign() {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            // 用一个不存在的 id 查询：只关心「请求有没有到达 fm-course 并正常返回」
            var list = courseClient.queryCourseSimpleList(List.of(-1L));
            r.put("feign", "OK");
            r.put("result", list);
            log.info("spike feign 调通：{}", list);
        } catch (Exception e) {
            r.put("feign", "FAILED");
            r.put("error", e.getClass().getName() + ": " + e.getMessage());
            log.error("spike feign 失败", e);
        }
        return r;
    }

    @Operation(summary = "⑤ Spring AI 真实调用（DeepSeek，OpenAI 兼容协议）")
    @GetMapping("/ai")
    public Map<String, Object> ai(@RequestParam(value = "q", defaultValue = "用一句话说明增肌为什么要吃够蛋白质") String q) {
        Map<String, Object> r = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            String answer = chatClientBuilder.build()
                    .prompt()
                    .user(q)
                    .call()
                    .content();
            r.put("springAi", "OK");
            r.put("question", q);
            r.put("answer", answer);
            r.put("elapsedMs", System.currentTimeMillis() - start);
            log.info("spike AI 调用成功，耗时 {}ms", r.get("elapsedMs"));
        } catch (Exception e) {
            r.put("springAi", "FAILED");
            r.put("error", e.getClass().getName() + ": " + e.getMessage());
            log.error("spike AI 调用失败", e);
        }
        return r;
    }
}
