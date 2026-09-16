package com.fitmind.learning.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.learning.domain.dto.QuestionFormDTO;
import com.fitmind.learning.domain.po.InteractionQuestion;
import com.fitmind.learning.domain.query.QuestionAdminPageQuery;
import com.fitmind.learning.domain.query.QuestionPageQuery;
import com.fitmind.learning.domain.vo.QuestionAdminVO;
import com.fitmind.learning.domain.vo.QuestionVO;

/**
 * 互动提问服务。
 */
public interface IInteractionQuestionService extends IService<InteractionQuestion> {

    /** 提问 */
    void saveQuestion(QuestionFormDTO dto);

    /** 问题分页（用户端；可按课程/动作过滤，可只看自己） */
    PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query);

    /** 问题详情（用户端；被隐藏的查不到） */
    QuestionVO queryQuestionById(Long id);

    /** 问题分页（管理端；含被隐藏的，匿名也返回真实用户） */
    PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query);

    /** 问题详情（管理端） */
    QuestionAdminVO queryQuestionByIdAdmin(Long id);

    /** 隐藏 / 显示问题（管理端） */
    void hiddenQuestion(Long id, Boolean hidden);

    /** 修改问题（仅本人） */
    void updateQuestion(Long id, QuestionFormDTO dto);

    /** 删除问题（仅本人；连带删除其下所有回答与评论） */
    void deleteQuestion(Long id);
}
