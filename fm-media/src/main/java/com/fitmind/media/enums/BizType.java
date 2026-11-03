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

    /**
     * 训练视频（M12-1 新增，供分片上传 target=media 使用）。
     *
     * <p>★ 与 `media-cover` 是两个前缀：视频本体在 `media/`，封面在 `media-cover/`。
     * 桶策略对 `media/` 下的 **mp4 拒绝匿名读**（付费内容），只对图片放行 ——
     * 所以这个前缀是"私有"的（见 {@link #isPublicRead()}）。</p>
     */
    MEDIA("media", "media/"),

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

    /**
     * 该业务前缀是否「公开可读」—— 决定秒传能不能跨用户命中。
     *
     * <p><b>为什么需要这个判断：</b>秒传只要知道 hash 就能拿到文件。
     * 对本来就是匿名可读的前缀（桶策略里的 5 个白名单前缀）这不构成泄露；
     * 但对「只有本人能看」的前缀，等于把"知道 hash"变成了"拿到别人的文件"。</p>
     *
     * <p>★★ <b>新增业务前缀时必须同时想清楚这一条</b>：如果它是私有的，
     * 在这里显式返回 {@code false}。写成 switch 表达式而不是返回常量 true，
     * 就是为了让"新加枚举值"直接在编译期报错，而不是悄悄继承一个放行规则。</p>
     */
    public boolean isPublicRead() {
        return switch (this) {
            case AVATAR, COURSE_COVER, ACTION_COVER, MEDIA_COVER, COMMON -> true;
            // ★ 视频本体必须签名才能播：允许跨用户秒传 = 知道 hash 就能拿到付费视频
            case MEDIA -> false;
        };
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
