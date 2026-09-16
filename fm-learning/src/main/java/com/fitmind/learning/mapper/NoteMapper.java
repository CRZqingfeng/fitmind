package com.fitmind.learning.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fitmind.learning.domain.po.Note;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 笔记 Mapper。
 */
@Mapper
public interface NoteMapper extends BaseMapper<Note> {

    /**
     * 查询某个训练动作/课程下的笔记列表，并标记「我是否采集过」。
     *
     * <p>⚠️ 常见实现这里用 {@code LEFT JOIN note n2 ON n2.gathered_note_id = n.id}，
     * 但<b>漏了 {@code AND n2.user_id = #{userId}}</b>，导致两个问题：
     * ① 只要任何人采集过这条笔记，"我采集过"就是 true；
     * ② 同一条笔记被 N 个人采集会产生 N 行重复，翻页条数直接错乱。</p>
     *
     * <p>我们用 {@code EXISTS} 子查询替代 JOIN —— 不只是补上 user_id，
     * 而是从根上保证一行原始笔记只对应一行结果（没有笛卡尔积的可能）。</p>
     */
    IPage<Note> queryNotePageBySectionId(Page<Note> page,
                                        @Param("userId") Long userId,
                                        @Param("courseId") Long courseId,
                                        @Param("sectionId") Long sectionId);

    /**
     * 管理端分页：附带「被采集次数」。
     */
    IPage<Note> queryNotePage(Page<Note> page, @Param("ew") Wrapper<Note> wrapper);

    /**
     * 查询采集过某条笔记的用户 id（管理端详情展示"采集者名单"）。
     */
    List<Long> queryNoteGathers(@Param("noteId") Long noteId);
}
