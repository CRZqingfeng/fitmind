package com.fitmind.learning.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.learning.domain.query.ReplyPageQuery;
import com.fitmind.learning.domain.vo.ReplyVO;
import com.fitmind.learning.service.IInteractionReplyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 互动回答接口（管理端）。
 */
@RestController
@RequestMapping("/admin/replies")
@RequiredArgsConstructor
@Tag(name = "管理端-互动问答")
public class InteractionReplyAdminController {

    private final IInteractionReplyService replyService;

    @Operation(summary = "回答分页（含被隐藏的）")
    @GetMapping("/page")
    public PageDTO<ReplyVO> queryReplyPage(ReplyPageQuery query) {
        return replyService.queryReplyPage(query, true);
    }

    @Operation(summary = "回答详情")
    @GetMapping("/{id}")
    public ReplyVO queryReplyById(
            @Parameter(description = "回答id", example = "1") @PathVariable("id") Long id) {
        return replyService.queryReplyById(id);
    }

    @Operation(summary = "隐藏 / 显示回答（隐藏顶层回答会连带隐藏其下评论）")
    @PutMapping("/{id}/hidden/{hidden}")
    public void hiddenReply(
            @Parameter(description = "回答id", example = "1") @PathVariable("id") Long id,
            @Parameter(description = "true-隐藏 false-显示") @PathVariable("hidden") Boolean hidden) {
        replyService.hiddenReply(id, hidden);
    }
}
