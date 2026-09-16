package com.fitmind.media.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 文件/媒资状态。
 */
@Getter
public enum FileStatus implements IEnum<Integer> {

    /** 上传中：已生成预签名地址，但还没确认对象真的传上去了 */
    UPLOADING(1, "上传中"),

    /** 已上传：服务端已向对象存储核实对象存在 */
    UPLOADED(2, "已上传"),

    /** 已使用：被业务方（如课程）引用 */
    PROCESSED(3, "已使用"),
    ;

    private final int value;

    private final String desc;

    FileStatus(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    /**
     * {@code @JsonValue}：让 Jackson 序列化成<b>数字</b>（1/2/3）而不是枚举名（UPLOADED）。
     *
     * <p>不加这个注解的话，接口返回的是 {@code "UPLOADED"}，
     * 而跨服务 DTO（fm-api 的 MediaDTO.status）声明的是 Integer，
     * Feign 反序列化会直接报 {@code Cannot deserialize Integer from String}。</p>
     */
    @Override
    @JsonValue
    public Integer getValue() {
        return value;
    }

    public static FileStatus of(int value) {
        for (FileStatus status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("文件状态不合法：" + value);
    }
}
