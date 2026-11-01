package com.fitmind.plan.service;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.plan.domain.dto.SportLogCreateDTO;
import com.fitmind.plan.domain.query.SportQuery;
import com.fitmind.plan.domain.vo.SportDictVO;
import com.fitmind.plan.domain.vo.SportLogResultVO;
import com.fitmind.plan.domain.vo.SportVO;

/**
 * 运动服务（运动库 + 运动记录）。
 */
public interface ISportService {

    /** 运动项目分页 */
    PageDTO<SportVO> queryPage(SportQuery query);

    /** 字典：有数据的分类 + 左侧入口（含「动作库」这个跳转入口） */
    SportDictVO queryDict();

    /** 记一次运动，返回 {logId, kcal} */
    SportLogResultVO createLog(SportLogCreateDTO dto);

    /** 删一条运动记录 */
    void removeLog(Long id);
}
