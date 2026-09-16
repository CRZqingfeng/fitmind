package com.fitmind.aigc.prompt;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地提示词来源（第二级）—— 读 classpath 下的 {@code prompts/{key}.txt}。
 *
 * <p>放在资源文件里而不是塞进 {@code application.yml}：提示词是几十行的长文本，
 * 混在 yml 里会把配置结构彻底淹没（想找"数据源在哪"得往下翻很久）。</p>
 *
 * <p>读到的内容会缓存在内存里（读一次用一辈子）—— 它在 jar 包里不会变，
 * 每次请求都去读一次文件系统纯属浪费。</p>
 */
@Slf4j
@Component
public class LocalPromptSource implements IPromptSource {

    private static final String DIR = "prompts/";

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @Override
    public String load(String key) {
        if (StrUtil.isBlank(key)) {
            return null;
        }
        // ⚠️ computeIfAbsent 的映射函数返回 null 时不会写入缓存 ——
        //    所以"文件不存在"的 key 每次都会再查一次 classpath。
        //    这一点点开销可以接受，换来的是"往 resources 里补一个文件就立刻生效"
        //    （若缓存了 null，开发时要重启才生效，白白浪费调试时间）。
        return cache.computeIfAbsent(key, this::readFromClasspath);
    }

    private String readFromClasspath(String key) {
        String path = DIR + key + ".txt";
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            log.debug("本地提示词加载成功：{}（{} 字）", path, text.length());
            return text;
        } catch (IOException e) {
            log.warn("本地提示词读取失败：{}", path, e);
            return null;
        }
    }

    @Override
    public String name() {
        return "local-file(resources/prompts)";
    }
}
