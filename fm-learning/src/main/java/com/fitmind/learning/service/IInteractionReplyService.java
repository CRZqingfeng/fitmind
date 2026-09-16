package com.fitmind.learning.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.learning.domain.dto.ReplyDTO;
import com.fitmind.learning.domain.po.InteractionReply;
import com.fitmind.learning.domain.query.ReplyPageQuery;
import com.fitmind.learning.domain.vo.ReplyVO;

/**
 * 互动回答服务（回答 + 楼中楼评论用同一张表）。
 */
public interface IInteractionReplyService extends IService<InteractionReply> {

    /** 回答 / 评论 */
    void saveReply(ReplyDTO dto);

    /**
     * 回答分页。
     *
     * @param forAdmin true-管理端（能看到被隐藏的、匿名的真实信息）
     */
    PageDTO<ReplyVO> queryReplyPage(ReplyPageQuery query, boolean forAdmin);

    /** 回答详情（管理端） */
    ReplyVO queryReplyById(Long id);

    /** 隐藏 / 显示回答（管理端；隐藏顶层回答会连带隐藏其下评论） */
    void hiddenReply(Long id, Boolean hidden);
}
