package com.fitmind.learning.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.learning.domain.query.NoteAdminPageQuery;
import com.fitmind.learning.domain.vo.NoteAdminDetailVO;
import com.fitmind.learning.domain.vo.NoteAdminVO;
import com.fitmind.learning.service.INoteService;
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
 * 学习笔记接口（管理端）。
 *
 * <p>⚠️ {@code /admin/**} 由 {@code AdminAuthInterceptor} 做角色校验（必须管理员）。</p>
 */
@RestController
@RequestMapping("/admin/notes")
@RequiredArgsConstructor
@Tag(name = "管理端-学习笔记")
public class NoteAdminController {

    private final INoteService noteService;

    @Operation(summary = "笔记分页（含被折叠的，附被采集次数）")
    @GetMapping("/page")
    public PageDTO<NoteAdminVO> queryNotePageForAdmin(NoteAdminPageQuery query) {
        return noteService.queryNotePageForAdmin(query);
    }

    @Operation(summary = "笔记详情（含作者手机号与采集者名单）")
    @GetMapping("/{id}")
    public NoteAdminDetailVO queryNoteDetailForAdmin(
            @Parameter(description = "笔记id", example = "1") @PathVariable("id") Long id) {
        return noteService.queryNoteDetailForAdmin(id);
    }

    @Operation(summary = "折叠 / 恢复笔记")
    @PutMapping("/{id}/hidden/{hidden}")
    public void hiddenNote(
            @Parameter(description = "笔记id", example = "1") @PathVariable("id") Long id,
            @Parameter(description = "true-折叠 false-恢复") @PathVariable("hidden") Boolean hidden) {
        noteService.hiddenNote(id, hidden);
    }
}
