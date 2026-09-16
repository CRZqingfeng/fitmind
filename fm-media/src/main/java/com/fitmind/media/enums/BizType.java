package com.fitmind.media.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 文件业务类型。
 *
 * <p>常见实现用的是 {@code platform}（区分腾讯云/阿里云/七牛），
 * 我们只有 MinIO 一个存储，"多云标识"是伪需求，
 * 改成更有用的 {@code bizType}：标识这张图用在哪儿，
 * 便于按业务清理孤儿图片、也便于统计各类图片占用。</p>
 *
 * <p>{@code path} 是对象存储在 bucket 里的目录前缀，
 * 生成 key 时直接拼在前面，避免所有文件平铺在根目录。</p>
 */
@Getter
public enum BizType implements IEnum<String> {

    /** 用户头像 */
    AVATAR("avatar", "avatar/"),

    /** 课程封面 */
    COURSE_COVER("course-cover", "course-cover/"),

    /** 训练动作封面 */
    ACTION_COVER("action-cover", "action-cover/"),

    /** 视频封面 */
    MEDIA_COVER("media-cover", "media-cover/"),

    /** 通用（兜底） */
    COMMON("common", "common/"),
    ;

    private final String value;

    /** 对象存储目录前缀 */
    private final String path;

    BizType(String value, String path) {
        this.value = value;
        this.path = path;
    }

    @Override
    public String getValue() {
        return value;
    }

    public static BizType of(String value) {
        for (BizType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return COMMON;
    }
}
