package com.fitmind.media.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 分片上传任务状态。
 *
 * <p>★ 与 {@link FileStatus} 分开定义，不要复用 —— 两者描述的不是一件事：
 * FileStatus 描述的是「文件记录」的生命周期（上传中/已上传/已使用），
 * 这里描述的是「一次分片上传过程」的生命周期。
 * 合并成一个枚举的后果是：以后给任务加「已过期」，文件侧也会凭空多出一个非法状态。</p>
 */
@Getter
public enum UploadStatus implements IEnum<Integer> {

    /** 上传中：还可以继续传分片，也可以被 init 复用（断点续传） */
    UPLOADING(1, "上传中"),

    /** 已完成：分片已合并成正式对象，file 记录已生成 */
    COMPLETED(2, "已完成"),

    /** 已取消：分片对象已清理，不可再传 */
    CANCELLED(3, "已取消"),
    ;

    private final int value;

    private final String desc;

    UploadStatus(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    @Override
    @JsonValue
    public Integer getValue() {
        return value;
    }

    public static UploadStatus of(int value) {
        for (UploadStatus status : values()) {
            if (status.value == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("上传任务状态不合法：" + value);
    }
}
