package com.fitmind.message.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.message.domain.query.UserInboxQuery;
import com.fitmind.message.domain.vo.UserInboxPageVO;
import com.fitmind.message.service.IUserInboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 我的消息（用户端）。
 *
 * <p>所有接口都只操作<b>当前登录用户</b>自己的消息 ——
 * 收件人取自 {@code UserContext}，前端传不了 userId。
 * 这类"查询/操作自己的数据"的接口，越权往往就是从"顺手接受一个 userId 参数"开始的。</p>
 */
@RestController
@RequestMapping("/messages")
@RequiredArgsConstructor
@Tag(name = "我的消息（用户端）")
public class UserInboxController {

    private final IUserInboxService inboxService;

    @Operation(summary = "我的消息分页（会先把新公告展开进来）")
    @GetMapping("/page")
    public PageDTO<UserInboxPageVO> page(UserInboxQuery query) {
        return inboxService.queryMyMessages(query);
    }

    @Operation(summary = "未读消息数（小程序红点）")
    @GetMapping("/unread-count")
    public int unreadCount() {
        return inboxService.countUnread();
    }

    @Operation(summary = "标记已读")
    @Parameters(@Parameter(name = "id", description = "消息id"))
    @PutMapping("/{id}/read")
    public void markRead(@PathVariable("id") Long id) {
        inboxService.markRead(id);
    }

    @Operation(summary = "全部已读")
    @PutMapping("/read-all")
    public void markAllRead() {
        inboxService.markAllRead();
    }
}
