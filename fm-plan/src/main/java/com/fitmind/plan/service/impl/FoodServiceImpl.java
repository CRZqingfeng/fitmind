package com.fitmind.plan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.plan.constants.DietConstants;
import com.fitmind.plan.constants.DietErrorInfo;
import com.fitmind.plan.domain.dto.FoodCreateDTO;
import com.fitmind.plan.domain.po.Food;
import com.fitmind.plan.domain.po.FoodFavorite;
import com.fitmind.plan.domain.query.FoodQuery;
import com.fitmind.plan.domain.vo.FoodDictVO;
import com.fitmind.plan.domain.vo.FoodVO;
import com.fitmind.plan.mapper.FoodFavoriteMapper;
import com.fitmind.plan.mapper.FoodMapper;
import com.fitmind.plan.service.IFoodService;
import com.fitmind.plan.utils.DictOrder;
import com.fitmind.plan.utils.FoodUnits;
import com.fitmind.plan.utils.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 食物库实现。
 *
 * <p><b>本类最容易写错的地方</b>：</p>
 * <ol>
 *   <li><b>把"收藏/我的/常见"当分类用</b>。它们是 scope，是"从哪里进来的"；
 *       分类是"这个食物是什么"。两者能叠加（收藏里的主食），塞进一个字段就叠加不了。</li>
 *   <li><b>分类字典写死 8 类</b>：数据集里没有中式/西式菜肴，写死就等于给用户
 *       三个永远空着的入口。这里从库里 distinct 取。</li>
 *   <li><b>删除食物时忘了清理收藏</b>：food 删了、food_favorite 还留着 food_id，
 *       下次"我的收藏"里会冒出一个查不到名字的幽灵。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FoodServiceImpl extends ServiceImpl<FoodMapper, Food> implements IFoodService {

    private final FoodFavoriteMapper favoriteMapper;

    @Override
    public PageDTO<FoodVO> queryPage(FoodQuery query) {
        Long userId = LoginUser.id();

        String scope = query.getScope() == null || query.getScope().isBlank()
                ? DietConstants.SCOPE_COMMON : query.getScope().trim();
        String category = trimToNull(query.getCategory());
        String keyword = trimToNull(query.getKeyword());

        LambdaQueryWrapper<Food> wrapper = Wrappers.<Food>lambdaQuery().eq(Food::getStatus, 1);

        if (DietConstants.SCOPE_FAVORITE.equals(scope)) {
            List<Long> favoriteIds = favoriteMapper.selectList(Wrappers.<FoodFavorite>lambdaQuery()
                            .eq(FoodFavorite::getUserId, userId))
                    .stream().map(FoodFavorite::getFoodId).toList();
            if (favoriteIds.isEmpty()) {
                // ★ 收藏为空要返回"空的分页"，而不是去查全表 —— 后者会让用户以为收藏里有全部食物
                return PageDTO.empty(0L, 0L);
            }
            wrapper.in(Food::getId, favoriteIds);
        } else if (DietConstants.SCOPE_MINE.equals(scope)) {
            wrapper.eq(Food::getOwnerId, userId);
        } else {
            // 常见 / 未指定：只给公共食物（用户自定义的自己去「我添加的」看）
            wrapper.eq(Food::getOwnerId, 0L);
        }

        wrapper.eq(category != null, Food::getCategory, category)
                .and(keyword != null, w -> w.like(Food::getName, keyword).or().like(Food::getAlias, keyword))
                // 常见位：靠 sort，不靠 id（导入顺序 ≠ 推荐顺序）
                .orderByAsc(Food::getSort)
                .orderByAsc(Food::getId);

        Page<Food> page = page(query.toMpPage("sort", true), wrapper);
        Set<Long> favoriteSet = favoriteSetOf(userId, page.getRecords());
        return PageDTO.of(page, f -> toVo(f, favoriteSet.contains(f.getId()), f.getOwnerId() > 0));
    }

    @Override
    public FoodDictVO queryDict() {
        // ★ 只返回库里真有数据的分类（方案 A）：写死 8 类会造出三个死入口
        List<String> categories = orderCategories(
                baseMapper.selectObjs(new QueryWrapper<Food>().select("DISTINCT category").eq("status", 1)));

        List<FoodDictVO.EntryVO> entries = new ArrayList<>();
        entries.add(new FoodDictVO.EntryVO(DietConstants.SCOPE_COMMON, "常见", "entry"));
        entries.add(new FoodDictVO.EntryVO(DietConstants.SCOPE_FAVORITE, "我的收藏", "entry"));
        entries.add(new FoodDictVO.EntryVO(DietConstants.SCOPE_MINE, "我添加的", "entry"));
        entries.add(new FoodDictVO.EntryVO("search", "搜索", "search"));
        for (String c : categories) {
            entries.add(new FoodDictVO.EntryVO(c, c, "category"));
        }

        List<FoodDictVO.MealTypeVO> meals = new ArrayList<>();
        for (int i = 0; i < DietConstants.MEAL_LABELS.size(); i++) {
            meals.add(new FoodDictVO.MealTypeVO(i + 1, DietConstants.MEAL_LABELS.get(i)));
        }

        FoodDictVO vo = new FoodDictVO();
        vo.setCategories(categories);
        vo.setMealTypes(meals);
        vo.setEntries(entries);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createFood(FoodCreateDTO dto) {
        Long userId = LoginUser.id();

        String name = dto.getName().trim();
        // 名字唯一（uk_food_name）：先查一次给出友好文案，最终仍由唯一索引兜底
        if (baseMapper.exists(Wrappers.<Food>lambdaQuery().eq(Food::getName, name))) {
            throw new BizIllegalException(400, DietErrorInfo.Msg.FOOD_NAME_DUPLICATE);
        }

        List<FoodUnits.Unit> units = dto.getUnits() == null ? List.of()
                : dto.getUnits().stream()
                .filter(u -> u.getUnit() != null && !u.getUnit().isBlank())
                .map(u -> new FoodUnits.Unit(u.getUnit().trim(),
                        u.getGram() == null ? BigDecimal.ONE : u.getGram()))
                .toList();

        Food food = new Food()
                .setName(name)
                .setAlias(null)
                .setCategory(dto.getCategory())
                .setKcalPer100g(dto.getKcalPer100g())
                .setProteinPer100g(dto.getProteinPer100g())
                .setCarbPer100g(dto.getCarbPer100g())
                .setFatPer100g(dto.getFatPer100g())
                .setUnitsJson(FoodUnits.toJson(units))
                .setDefaultUnit("克")
                .setEmoji(dto.getEmoji() == null || dto.getEmoji().isBlank() ? "🍽" : dto.getEmoji())
                .setSort(0)
                .setOwnerId(userId)
                .setStatus(1);
        try {
            save(food);
        } catch (DuplicateKeyException e) {
            // 上面查过一次仍然撞键 = 并发下另一个请求刚建了同名食物
            throw new BizIllegalException(400, DietErrorInfo.Msg.FOOD_NAME_DUPLICATE);
        }
        log.info("创建自定义食物：userId={}, foodId={}, name={}", userId, food.getId(), name);
        return food.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeFood(Long id) {
        Long userId = LoginUser.id();
        Food food = getById(id);
        if (food == null || !Integer.valueOf(1).equals(food.getStatus())) {
            throw new BizIllegalException(400, DietErrorInfo.Msg.FOOD_NOT_EXISTS);
        }
        if (!userId.equals(food.getOwnerId())) {
            throw new BizIllegalException(400, DietErrorInfo.Msg.FOOD_NOT_OWNER);
        }
        // ★ 先清收藏：不清的话"我的收藏"里会留一条查不到名字的记录
        favoriteMapper.delete(Wrappers.<FoodFavorite>lambdaQuery().eq(FoodFavorite::getFoodId, id));
        removeById(id);
        log.info("删除自定义食物：userId={}, foodId={}", userId, id);
    }

    @Override
    public void favorite(Long foodId) {
        Long userId = LoginUser.id();
        requireVisible(foodId, userId);
        // 唯一索引 uk_food_fav 兜底：重复收藏不报错（用户连点两下是常态）
        if (favoriteMapper.exists(Wrappers.<FoodFavorite>lambdaQuery()
                .eq(FoodFavorite::getUserId, userId).eq(FoodFavorite::getFoodId, foodId))) {
            return;
        }
        try {
            favoriteMapper.insert(new FoodFavorite().setUserId(userId).setFoodId(foodId));
        } catch (DuplicateKeyException e) {
            log.debug("重复收藏，忽略：userId={}, foodId={}", userId, foodId);
        }
    }

    @Override
    public void unfavorite(Long foodId) {
        Long userId = LoginUser.id();
        favoriteMapper.delete(Wrappers.<FoodFavorite>lambdaQuery()
                .eq(FoodFavorite::getUserId, userId).eq(FoodFavorite::getFoodId, foodId));
    }

    // ==================== 内部 ====================

    private Set<Long> favoriteSetOf(Long userId, List<Food> foods) {
        if (foods.isEmpty()) {
            return Collections.emptySet();
        }
        List<Long> ids = foods.stream().map(Food::getId).toList();
        return favoriteMapper.selectList(Wrappers.<FoodFavorite>lambdaQuery()
                        .eq(FoodFavorite::getUserId, userId)
                        .in(FoodFavorite::getFoodId, ids))
                .stream().map(FoodFavorite::getFoodId).collect(Collectors.toSet());
    }

    /** 按固定顺序排分类；不认识的分类放到最后（防止导入脚本新增了分类就消失） */
    private List<String> orderCategories(List<Object> raw) {
        // ★ 实现在 DictOrder（动作字典也用同一套）：三处各写一份的话，
        //   迟早有一处忘了"未列出的追加在后"，左侧栏顺序就会随 MySQL 返回顺序抖动。
        return DictOrder.order(raw, DietConstants.FOOD_CATEGORY_ORDER);
    }

    private void requireVisible(Long foodId, Long userId) {
        Food food = getById(foodId);
        if (food == null || !Integer.valueOf(1).equals(food.getStatus())) {
            throw new BizIllegalException(400, DietErrorInfo.Msg.FOOD_NOT_EXISTS);
        }
        // 别人的自定义食物看不见，更不能收藏
        if (food.getOwnerId() != null && food.getOwnerId() > 0 && !food.getOwnerId().equals(userId)) {
            throw new BizIllegalException(400, DietErrorInfo.Msg.FOOD_NOT_EXISTS);
        }
    }

    private FoodVO toVo(Food food, boolean favorite, boolean mine) {
        FoodVO vo = new FoodVO();
        vo.setId(food.getId());
        vo.setName(food.getName());
        vo.setAlias(food.getAlias());
        vo.setCategory(food.getCategory());
        vo.setKcalPer100g(food.getKcalPer100g());
        vo.setProteinPer100g(food.getProteinPer100g());
        vo.setCarbPer100g(food.getCarbPer100g());
        vo.setFatPer100g(food.getFatPer100g());
        vo.setEmoji(food.getEmoji());
        vo.setDefaultUnit(food.getDefaultUnit());
        vo.setUnits(FoodUnits.parse(food.getUnitsJson()).stream()
                .map(u -> {
                    FoodVO.UnitVO uv = new FoodVO.UnitVO();
                    uv.setUnit(u.unit());
                    uv.setGram(u.gram());
                    return uv;
                })
                .toList());
        vo.setFavorite(favorite);
        vo.setMine(mine);
        vo.setKcalText(food.getKcalPer100g() + " 千卡/100g");
        return vo;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
