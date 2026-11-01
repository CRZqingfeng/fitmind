package com.fitmind.plan.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.plan.domain.po.Exercise;
import com.fitmind.plan.domain.query.ExerciseQuery;
import com.fitmind.plan.domain.vo.DictVO;
import com.fitmind.plan.domain.vo.ExerciseDetailVO;
import com.fitmind.plan.domain.vo.ExerciseVO;

/**
 * 动作库服务。
 */
public interface IExerciseService extends IService<Exercise> {

    /** 动作库分页（category / bodyPart / equipment 是 AND 关系） */
    PageDTO<ExerciseVO> queryPage(ExerciseQuery query);

    /** 动作详情（含肌群与步骤） */
    ExerciseDetailVO queryDetail(Long id);

    /** 字典：分类 / 部位 / 器械 / 难度 / 组类型 / 星期 */
    DictVO queryDict();
}
