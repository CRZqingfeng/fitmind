package com.fitmind.message.domain.query;

import com.fitmind.common.domain.query.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 我的消息分页查询。
 *
 * <p>⚠️ <b>没有 userId 字段，是故意的</b>：收件人固定取当前登录用户
 * （{@code UserContext}）。如果让前端传，改个参数就能翻别人的私信 ——
 * 这类"看起来无害的查询参数"是越权最常见的入口。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "我的消息分页查询")
public class UserInboxQuery extends PageQuery {

    @Schema(description = "是否已读：0-未读 1-已读；不传查全部")
    private Integer isRead;

    @Schema(description = "通知类型：0-系统 1-笔记 2-问答 3-其它 4-私信；不传查全部")
    private Integer type;
}
