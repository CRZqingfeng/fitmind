package com.fitmind.learning.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.learning.domain.dto.ReplyDTO;
import com.fitmind.learning.domain.query.ReplyPageQuery;
import com.fitmind.learning.domain.vo.ReplyVO;
import com.fitmind.learning.service.IInteractionReplyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 互动回答接口（用户端）。
 */
@RestController
@RequestMapping("/replies")
@RequiredArgsConstructor
@Tag(name = "互动问答相关接口")
public class InteractionReplyController {

    private final IInteractionReplyService replyService;

    @Operation(summary = "回答 / 评论（answerId 非 0 表示评论某条回答）")
    @PostMapping
    public void saveReply(@Valid @RequestBody ReplyDTO dto) {
        replyService.saveReply(dto);
    }

    @Operation(summary = "回答分页（传 questionId 查回答；传 answerId 查评论）")
    @GetMapping("/page")
    public PageDTO<ReplyVO> queryReplyPage(ReplyPageQuery query) {
        return replyService.queryReplyPage(query, false);
    }
}
