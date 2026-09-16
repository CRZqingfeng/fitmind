package com.fitmind.learning.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.learning.domain.dto.NoteFormDTO;
import com.fitmind.learning.domain.po.Note;
import com.fitmind.learning.domain.query.NoteAdminPageQuery;
import com.fitmind.learning.domain.query.NotePageQuery;
import com.fitmind.learning.domain.vo.NoteAdminDetailVO;
import com.fitmind.learning.domain.vo.NoteAdminVO;
import com.fitmind.learning.domain.vo.NoteVO;

/**
 * 学习笔记服务。
 */
public interface INoteService extends IService<Note> {

    /** 保存笔记（写笔记送积分） */
    void saveNote(NoteFormDTO dto);

    /** 修改笔记（仅本人；采集来的笔记不能改成公开） */
    void updateNote(NoteFormDTO dto);

    /** 删除我的笔记（仅本人） */
    void removeMyNote(Long id);

    /** 笔记分页（用户端；可按课程/动作过滤，可只看我的） */
    PageDTO<NoteVO> queryNotePage(NotePageQuery query);

    /** 采集他人笔记（复制一份私有副本） */
    void gatherNote(Long id);

    /**
     * 取消采集。
     *
     * @param id <b>原笔记 id</b>（不是采集副本的 id）—— 与常见实现保持一致
     */
    void removeGatherNote(Long id);

    /** 笔记分页（管理端；含被折叠的，附被采集次数） */
    PageDTO<NoteAdminVO> queryNotePageForAdmin(NoteAdminPageQuery query);

    /** 笔记详情（管理端；含作者手机号与采集者名单） */
    NoteAdminDetailVO queryNoteDetailForAdmin(Long id);

    /** 折叠 / 恢复笔记（管理端） */
    void hiddenNote(Long id, boolean hidden);
}
