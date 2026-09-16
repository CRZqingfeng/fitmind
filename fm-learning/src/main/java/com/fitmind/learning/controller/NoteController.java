package com.fitmind.learning.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.learning.domain.dto.NoteFormDTO;
import com.fitmind.learning.domain.query.NotePageQuery;
import com.fitmind.learning.domain.vo.NoteVO;
import com.fitmind.learning.service.INoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学习笔记接口（用户端）。
 *
 * <p>网关前缀 {@code /ls/**}，对外是 {@code /ls/notes/**}，需登录。</p>
 */
@RestController
@RequestMapping("/notes")
@RequiredArgsConstructor
@Tag(name = "学习笔记相关接口")
public class NoteController {

    private final INoteService noteService;

    @Operation(summary = "保存笔记（写笔记送积分）")
    @PostMapping
    public void saveNote(@Valid @RequestBody NoteFormDTO dto) {
        noteService.saveNote(dto);
    }

    @Operation(summary = "修改笔记（仅本人；采集来的笔记不能改为公开）")
    @PutMapping("/{id}")
    public void updateNote(
            @Parameter(description = "笔记id", example = "1") @PathVariable("id") Long id,
            @Valid @RequestBody NoteFormDTO dto) {
        dto.setId(id);
        noteService.updateNote(dto);
    }

    @Operation(summary = "删除我的笔记")
    @DeleteMapping("/{id}")
    public void removeMyNote(
            @Parameter(description = "笔记id", example = "1") @PathVariable("id") Long id) {
        noteService.removeMyNote(id);
    }

    @Operation(summary = "笔记分页（课程 / 动作至少传一个；onlyMine=true 只看我的）")
    @GetMapping("/page")
    public PageDTO<NoteVO> queryNotePage(NotePageQuery query) {
        return noteService.queryNotePage(query);
    }

    @Operation(summary = "采集他人笔记（复制一份私有副本）")
    @PostMapping("/gathers/{id}")
    public void gatherNote(
            @Parameter(description = "原笔记id", example = "1") @PathVariable("id") Long id) {
        noteService.gatherNote(id);
    }

    @Operation(summary = "取消采集（传原笔记id）")
    @DeleteMapping("/gathers/{id}")
    public void removeGatherNote(
            @Parameter(description = "原笔记id", example = "1") @PathVariable("id") Long id) {
        noteService.removeGatherNote(id);
    }
}
