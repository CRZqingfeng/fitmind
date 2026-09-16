package com.fitmind.message.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.domain.query.PageQuery;
import com.fitmind.message.domain.dto.NoticeTemplateFormDTO;
import com.fitmind.message.domain.dto.PublicNoticeFormDTO;
import com.fitmind.message.domain.po.NoticeTemplate;
import com.fitmind.message.domain.po.PublicNotice;
import com.fitmind.message.service.INoticeService;
import com.fitmind.message.service.IPublicNoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 消息管理端（模板 + 公告）。
 *
 * <p><b>★ 路径前缀 {@code /admin/} 是权限边界</b>：
 * 由 {@code AdminAuthInterceptor} 强制校验管理员身份。</p>
 *
 * <p><b>定时发布公告怎么做</b>：直接调 {@code POST /admin/public-notices}，
 * 把 {@code pushTime} 填成未来时间即可 —— 公告是 pull 模式，
 * {@code expandToInbox} 里本来就有"只展开 push_time <= now"的条件，
 * 到点用户自然能看到。<b>不需要单独的定时任务</b>。</p>
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Tag(name = "消息管理端（模板 / 公告）")
public class NoticeAdminController {

    private final INoticeService noticeService;
    private final IPublicNoticeService publicNoticeService;

    // ==================== 通知模板 ====================

    @Operation(summary = "新增通知模板")
    @PostMapping("/notice-templates")
    public Long saveTemplate(@RequestBody @Valid NoticeTemplateFormDTO dto) {
        return noticeService.saveTemplate(dto);
    }

    @Operation(summary = "修改通知模板（代号不可改）")
    @PutMapping("/notice-templates")
    public void updateTemplate(@RequestBody @Valid NoticeTemplateFormDTO dto) {
        noticeService.updateTemplate(dto);
    }

    @Operation(summary = "删除通知模板")
    @Parameters(@Parameter(name = "id", description = "模板id"))
    @DeleteMapping("/notice-templates/{id}")
    public void removeTemplate(@PathVariable("id") Long id) {
        noticeService.removeTemplate(id);
    }

    @Operation(summary = "通知模板分页")
    @GetMapping("/notice-templates/page")
    public PageDTO<NoticeTemplate> templatePage(PageQuery query) {
        return noticeService.queryTemplates(query);
    }

    @Operation(summary = "通知模板详情")
    @Parameters(@Parameter(name = "id", description = "模板id"))
    @GetMapping("/notice-templates/{id}")
    public NoticeTemplate template(@PathVariable("id") Long id) {
        return noticeService.getTemplate(id);
    }

    // ==================== 公告 ====================

    @Operation(summary = "发布公告（pushTime 填未来时间即定时生效）")
    @PostMapping("/public-notices")
    public Long publish(@RequestBody @Valid PublicNoticeFormDTO dto) {
        return publicNoticeService.publish(dto);
    }

    @Operation(summary = "公告分页")
    @GetMapping("/public-notices/page")
    public PageDTO<PublicNotice> noticePage(PageQuery query) {
        return publicNoticeService.queryPage(query);
    }
}
