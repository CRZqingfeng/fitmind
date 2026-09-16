package com.fitmind.course.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.UserContext;
import com.fitmind.course.constants.CourseErrorInfo;
import com.fitmind.course.domain.dto.CategoryAddDTO;
import com.fitmind.course.domain.dto.CategoryDisableOrEnableDTO;
import com.fitmind.course.domain.dto.CategoryListDTO;
import com.fitmind.course.domain.dto.CategoryUpdateDTO;
import com.fitmind.course.domain.po.Category;
import com.fitmind.course.domain.po.Course;
import com.fitmind.course.domain.vo.CategoryVO;
import com.fitmind.course.mapper.CategoryMapper;
import com.fitmind.course.mapper.CourseMapper;
import com.fitmind.course.service.ICategoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 课程分类服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements ICategoryService {

    /** 一级分类的父 id */
    private static final long ROOT_PARENT_ID = 0L;
    /** 最大层级 */
    private static final int MAX_LEVEL = 3;

    private final CourseMapper courseMapper;

    @Override
    public List<CategoryVO> list(CategoryListDTO dto) {
        LambdaQueryWrapper<Category> wrapper = new LambdaQueryWrapper<Category>()
                .eq(dto.getLevel() != null, Category::getLevel, dto.getLevel())
                .eq(dto.getParentId() != null, Category::getParentId, dto.getParentId())
                .eq(dto.getStatus() != null, Category::getStatus, dto.getStatus())
                .orderByDesc(Category::getPriority)
                .orderByAsc(Category::getId);
        List<Category> all = list(wrapper);

        // 是否组装成树：只有不带 level/parentId 条件的全量查询才需要树
        boolean needTree = Boolean.TRUE.equals(dto.getTree())
                && dto.getLevel() == null && dto.getParentId() == null;
        return needTree ? buildTree(all) : toVOList(all);
    }

    @Override
    public CategoryVO getCategoryById(Long id) {
        Category category = getById(id);
        if (category == null) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATEGORY_NOT_EXISTS);
        }
        return toVO(category);
    }

    @Override
    public void add(CategoryAddDTO dto) {
        // 1. 由父分类推导层级
        int level = resolveLevel(dto.getParentId());
        // 2. 同一父分类下不允许重名
        checkNameDuplicate(dto.getName(), dto.getParentId(), null);

        Category category = new Category()
                .setName(dto.getName())
                .setParentId(dto.getParentId())
                .setLevel(level)
                .setPriority(dto.getPriority() == null ? 0 : dto.getPriority())
                .setStatus(1)
                .setCreater(UserContext.getUser())
                .setDeleted(0);
        save(category);
        log.info("新增分类：id={}, name={}, level={}", category.getId(), category.getName(), level);
    }

    @Override
    public void update(CategoryUpdateDTO dto) {
        Category category = getById(dto.getId());
        if (category == null) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATEGORY_NOT_EXISTS);
        }
        checkNameDuplicate(dto.getName(), category.getParentId(), dto.getId());

        updateById(new Category()
                .setId(dto.getId())
                .setName(dto.getName())
                .setPriority(dto.getPriority())
                .setUpdater(UserContext.getUser()));
    }

    @Override
    public void disableOrEnable(CategoryDisableOrEnableDTO dto) {
        Category category = getById(dto.getId());
        if (category == null) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATEGORY_NOT_EXISTS);
        }
        updateById(new Category()
                .setId(dto.getId())
                .setStatus(dto.getStatus())
                .setUpdater(UserContext.getUser()));
    }

    @Override
    public void delete(Long id) {
        Category category = getById(id);
        if (category == null) {
            return;
        }
        // 1. 有子分类不能删（避免出现孤儿节点）
        long childCount = count(new LambdaQueryWrapper<Category>().eq(Category::getParentId, id));
        if (childCount > 0) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATEGORY_HAS_CHILDREN);
        }
        // 2. 已被课程使用不能删（避免课程指向不存在的分类）
        if (isUsedByCourse(id, category.getLevel())) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATEGORY_IN_USE);
        }
        removeById(id);
    }

    @Override
    public List<CategoryVO> getAll() {
        List<Category> all = list(new LambdaQueryWrapper<Category>()
                .orderByDesc(Category::getPriority)
                .orderByAsc(Category::getId));
        return buildTree(all);
    }

    @Override
    public List<CategoryVO> getAllOfOneLevel(Integer level) {
        List<Category> all = list(new LambdaQueryWrapper<Category>()
                .eq(Category::getLevel, level)
                .orderByDesc(Category::getPriority)
                .orderByAsc(Category::getId));
        return toVOList(all);
    }

    // ==================== 内部方法 ====================

    /**
     * 由父 id 推导层级。
     *
     * <p>一级分类的父 id 是 0；其余层级 = 父分类层级 + 1。
     * 超过三级一律拒绝——层级失控会让整棵树没法维护。</p>
     */
    private int resolveLevel(Long parentId) {
        if (parentId == null || parentId == ROOT_PARENT_ID) {
            return 1;
        }
        Category parent = getById(parentId);
        if (parent == null) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATEGORY_NOT_EXISTS);
        }
        int level = parent.getLevel() + 1;
        if (level > MAX_LEVEL) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATEGORY_LEVEL_ILLEGAL);
        }
        return level;
    }

    /** 同一父分类下重名校验；excludeId 用于更新时排除自己 */
    private void checkNameDuplicate(String name, Long parentId, Long excludeId) {
        long count = count(new LambdaQueryWrapper<Category>()
                .eq(Category::getName, name)
                .eq(Category::getParentId, parentId)
                .ne(excludeId != null, Category::getId, excludeId));
        if (count > 0) {
            throw new BizIllegalException(CourseErrorInfo.Msg.CATEGORY_NAME_EXISTS);
        }
    }

    /** 分类是否已被课程引用 */
    private boolean isUsedByCourse(Long cateId, Integer level) {
        LambdaQueryWrapper<Course> wrapper = new LambdaQueryWrapper<>();
        switch (level) {
            case 1 -> wrapper.eq(Course::getFirstCateId, cateId);
            case 2 -> wrapper.eq(Course::getSecondCateId, cateId);
            case 3 -> wrapper.eq(Course::getThirdCateId, cateId);
            default -> {
                return false;
            }
        }
        return courseMapper.selectCount(wrapper) > 0;
    }

    /** 平铺列表 -> VO */
    private List<CategoryVO> toVOList(List<Category> list) {
        return list.stream().map(this::toVO).collect(Collectors.toList());
    }

    private CategoryVO toVO(Category c) {
        CategoryVO vo = new CategoryVO();
        vo.setId(c.getId());
        vo.setName(c.getName());
        vo.setParentId(c.getParentId());
        vo.setLevel(c.getLevel());
        vo.setPriority(c.getPriority());
        vo.setStatus(c.getStatus());
        vo.setStatusDesc(c.getStatus() != null && c.getStatus() == 1 ? "启用" : "禁用");
        vo.setCreateTime(c.getCreateTime());
        return vo;
    }

    /**
     * 把平铺列表组装成三级树。
     *
     * <p>做法：先按 parentId 分组，再从根节点递归挂子节点。
     * 比"每层查一次库"少打 N 次数据库。</p>
     */
    private List<CategoryVO> buildTree(List<Category> all) {
        List<CategoryVO> voList = toVOList(all);
        Map<Long, List<CategoryVO>> byParent = voList.stream()
                .collect(Collectors.groupingBy(CategoryVO::getParentId));

        // 先把每个节点的 children 挂好
        voList.forEach(vo -> vo.setChildren(byParent.getOrDefault(vo.getId(), new ArrayList<>())));
        // 再取根节点（parentId = 0）
        return byParent.getOrDefault(ROOT_PARENT_ID, new ArrayList<>());
    }
}
