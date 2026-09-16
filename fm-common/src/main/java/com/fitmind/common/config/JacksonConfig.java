package com.fitmind.common.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 全局配置。
 *
 * <h3>解决什么问题</h3>
 * <p>Jackson 默认的 {@code LocalDateTime} 反序列化<b>只认 ISO 格式</b>
 * （{@code 2026-01-01T00:00:00}，带一个 {@code T}）。但国内前端、
 * Postman、各种文档示例里几乎都写 {@code 2026-01-01 00:00:00}（空格分隔），
 * 于是一调就报：</p>
 * <pre>
 * JSON parse error: Cannot deserialize value of type `java.time.LocalDateTime`
 * from String "2026-01-01 00:00:00": Text ... could not be parsed at index 10
 * </pre>
 * <p>这个报错还相当难猜 —— 提示说"index 10 解析不了"，而第 10 位恰好是
 * 那个空格或 {@code T}，容易让人怀疑是时区、长度、格式串写错。</p>
 *
 * <h3>★ 为什么用全局配置而不是给每个字段加 @JsonFormat</h3>
 * <p>给单个字段加 {@code @JsonFormat(pattern = "...")} 是个陷阱：
 * 它把格式<b>钉死成一种</b>，原来能用的 ISO 格式反而不能用了
 * （改完 A 调用方通了、B 调用方挂了）。而且字段一多就要加几十遍。</p>
 * <p>做成<b>宽容的反序列化器</b>，一次配置、所有服务的所有 LocalDateTime 都受益，
 * 而且不需要调用方去猜"到底该传哪种格式"。<b>接口对输入格式的宽容度，
 * 应该由服务端提供，而不是写进对接文档让别人踩坑。</b></p>
 */
@Slf4j
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeCustomizer() {
        return builder -> builder.deserializerByType(LocalDateTime.class, new FlexibleLocalDateTimeDeserializer());
    }

    /**
     * ★★ Long 统一序列化为 String（解决"雪花 id 在前端精度丢失"）。
     *
     * <h3>问题</h3>
     * <p>JavaScript 的 {@code Number} 是 IEEE-754 双精度浮点，<b>安全整数上限是 2^53-1</b>
     * （9007199254740991，16 位）。而雪花 id 是 19 位（约 1.7×10^18），
     * 一旦用 {@code JSON.parse} 解析就会<b>静默失真</b>：</p>
     * <pre>
     *   Java 返回:  1757000000000000001
     *   JS 得到:    1757000000000000000   ← 末位被抹平
     *   拿它去查库/调接口 → 查不到（报"不存在"），而且完全不报错
     * </pre>
     * <p>本项目就踩过：发券接口返回的券id 被 JS 改掉末位，
     * 再拿它去领券就报"优惠券不存在"，排查时十分费解。</p>
     *
     * <h3>为什么做成全局</h3>
     * <p>项目里的 VO 早就习惯性加 {@code @JsonSerialize(using = ToStringSerializer.class)}，
     * 但那只覆盖"手写过的 VO" —— 一旦某个 PO 直接当出入参（比如 {@code Coupon}），
     * 就漏了。与其靠人记住"每个 Long 字段都要加注解"，不如在<b>序列化层统一处理</b>：
     * 所有 Long 出参都是字符串，前端当字符串处理即可（本来也该如此）。</p>
     *
     * <p>⚠️ 代价：分页 {@code total}、自增主键这类小整数也会变成字符串，
     * 前端若要参与计算需 {@code Number(x)} 一次。这个代价换来的是"再也不会因为
     * 精度丢失而出现莫名其妙的查不到"，值得。</p>
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> builder
                .serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance);
    }

    /**
     * 宽容的 LocalDateTime 反序列化器：ISO 与空格格式都接受。
     *
     * <p>按优先级依次尝试：</p>
     * <ol>
     *   <li>ISO（{@code 2026-01-01T00:00:00}，可带毫秒）—— Jackson 的默认输出，必须支持</li>
     *   <li>空格分隔（{@code 2026-01-01 00:00:00}）—— 国内前端与文档的常见写法</li>
     *   <li>纯日期（{@code 2026-01-01}）—— 按当天 00:00:00 处理</li>
     * </ol>
     */
    static class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

        private static final DateTimeFormatter SPACE_SECONDS =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        private static final DateTimeFormatter SPACE_MILLIS =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String text = p.getText();
            if (text == null) {
                return null;
            }
            String s = text.trim();
            if (s.isEmpty()) {
                return null;
            }
            // 1. ISO（Jackson 默认输出格式，优先尝试）
            try {
                return LocalDateTime.parse(s);
            } catch (Exception ignored) {
                // 继续尝试其他格式
            }
            // 2. 空格分隔（带毫秒）
            try {
                return LocalDateTime.parse(s, SPACE_MILLIS);
            } catch (Exception ignored) {
                // 继续
            }
            // 3. 空格分隔（不带毫秒）—— 最常见
            try {
                return LocalDateTime.parse(s, SPACE_SECONDS);
            } catch (Exception ignored) {
                // 继续
            }
            // 4. 只有日期，按当天 0 点
            try {
                return LocalDate.parse(s).atStartOfDay();
            } catch (Exception e) {
                throw new IOException("无法解析日期时间：'" + text
                        + "'，支持 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-ddTHH:mm:ss 或 yyyy-MM-dd", e);
            }
        }
    }
}
