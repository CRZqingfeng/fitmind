package com.fitmind.media.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 初始化分片上传（第 1 步）。
 *
 * <p>这个请求同时承担三件事，所以字段一个都不能少：</p>
 * <ol>
 *   <li><b>秒传判定</b>：{@code hash + size + bizType} 命中已有对象 → 直接返回文件</li>
 *   <li><b>断点查询</b>：同一 hash 有未完成任务 → 返回已传分片列表</li>
 *   <li><b>新建任务</b>：都没有 → 生成任务与目标对象 key</li>
 * </ol>
 */
@Data
@Schema(description = "初始化分片上传")
public class InitUploadDTO {

    @NotBlank(message = "文件 hash 不能为空")
    @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "hash 必须是 64 位十六进制（SHA-256）")
    @Schema(description = "文件内容的 SHA-256（小写 hex，64 位）",
            example = "9f2c0f8f5b6c1a3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5")
    private String hash;

    @NotBlank(message = "文件名不能为空")
    @Schema(description = "原始文件名（决定对象后缀与 Content-Type）", example = "深蹲标准动作.mp4")
    private String filename;

    @NotNull(message = "文件大小不能为空")
    @Min(value = 1, message = "文件大小必须大于 0")
    @Schema(description = "文件总大小，单位字节", example = "209715200")
    private Long size;

    @Schema(description = "业务类型，决定对象前缀；不传按 common", example = "action-cover")
    private String bizType;

    /**
     * 合并后产出什么。
     *
     * <ul>
     *   <li>{@code file}（默认）：只落 {@code file} 表，用于图片/附件</li>
     *   <li>{@code media}：额外落一条 {@code media} 记录，返回 mediaId，
     *       供「动作考核视频 / 课程视频」沿用现有的播放与引用逻辑</li>
     * </ul>
     *
     * <p>★ 为什么不让后端自己从 bizType 猜：`media` 前缀也可能存图片（封面）。
     * "产出哪种记录"是<b>业务意图</b>，不该从目录名反推。</p>
     */
    @Schema(description = "产出类型：file-通用文件（默认）/ media-视频媒资", example = "media")
    private String target;

    @Schema(description = "期望分片大小（字节）。小于 5MiB 会被夹到 5MiB —— S3 硬约束", example = "5242880")
    private Integer chunkSize;
}
