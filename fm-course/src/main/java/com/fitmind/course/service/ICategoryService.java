package com.fitmind.course.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.course.domain.dto.CategoryAddDTO;
import com.fitmind.course.domain.dto.CategoryDisableOrEnableDTO;
import com.fitmind.course.domain.dto.CategoryListDTO;
import com.fitmind.course.domain.dto.CategoryUpdateDTO;
import com.fitmind.course.domain.po.Category;
import com.fitmind.course.domain.vo.CategoryVO;

import java.util.List;

/**
 * 课程分类服务。
 *
 * <p>分类是三级树：训练目标 → 训练部位 → 器械场景。</p>
 */
public interface ICategoryService extends IService<Category> {

    /** 查询分类（默认返回树形结构） */
    List<CategoryVO> list(CategoryListDTO dto);

    /** 查询单个分类详情 */
    CategoryVO getCategoryById(Long id);

    /** 新增分类（层级由父分类推导） */
    void add(CategoryAddDTO dto);

    /** 更新分类名称/排序 */
    void update(CategoryUpdateDTO dto);

    /** 启用 / 停用分类 */
    void disableOrEnable(CategoryDisableOrEnableDTO dto);

    /** 删除分类（有子分类或已被课程使用时拒绝） */
    void delete(Long id);

    /** 查询全部分类（树形），供前端下拉选择使用 */
    List<CategoryVO> getAll();

    /** 按层级查询分类（平铺），如取全部「训练目标」 */
    List<CategoryVO> getAllOfOneLevel(Integer level);
}
