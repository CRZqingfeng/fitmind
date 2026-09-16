package com.fitmind.remark.controller;

import com.fitmind.remark.domain.dto.LikeRecordFormDTO;
import com.fitmind.remark.service.ILikedRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 点赞接口。
 *
 * <p>网关前缀 {@code /rs/**}，对外是 {@code /rs/remarks/**}。
 * 点赞与查状态需登录；{@code /rebuild} 是内部恢复接口。</p>
 */
@RestController
@RequestMapping("/remarks")
@RequiredArgsConstructor
@Tag(name = "点赞业务相关接口")
public class LikedRecordController {

    private final ILikedRecordService likedRecordService;

    @Operation(summary = "点赞或取消点赞")
    @PostMapping("/likes")
    public void addLikeRecord(@Valid @RequestBody LikeRecordFormDTO dto) {
        likedRecordService.addLikeRecord(dto);
    }

    @Operation(summary = "批量查询当前用户的点赞状态")
    @GetMapping("/likes/list")
    public Set<Long> isBizLiked(
            @Parameter(description = "业务类型：QA-问答回答 NOTE-笔记", example = "QA")
            @RequestParam("bizType") String bizType,
            @Parameter(description = "业务id 集合，逗号分隔") @RequestParam("bizIds") List<Long> bizIds) {
        return likedRecordService.isBizLiked(bizType, bizIds);
    }

    /**
     * 【内部】故障恢复：从 DB 明细重建 Redis Set 并标脏。
     *
     * <p>Redis 数据丢失后调用，避免 {@code SCARD} 归零把业务表的 liked_times 写成 0。</p>
     */
    @Operation(summary = "【内部】从 DB 重建点赞 Redis 数据", hidden = true)
    @PostMapping("/likes/rebuild")
    public int rebuild(
            @Parameter(description = "业务类型") @RequestParam("bizType") String bizType,
            @Parameter(description = "业务id 集合，逗号分隔") @RequestParam("bizIds") List<Long> bizIds) {
        return likedRecordService.rebuild(bizType, bizIds);
    }
}
