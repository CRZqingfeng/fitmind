package com.fitmind.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fitmind.api.dto.course.CourseSimpleDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.common.utils.UserContext;
import com.fitmind.trade.config.TradeProperties;
import com.fitmind.trade.constants.TradeConstants;
import com.fitmind.trade.constants.TradeErrorInfo;
import com.fitmind.trade.domain.po.TradeCart;
import com.fitmind.trade.domain.vo.CartVO;
import com.fitmind.trade.mapper.TradeCartMapper;
import com.fitmind.trade.mapper.TradeOrderDetailMapper;
import com.fitmind.trade.service.CourseQueryHelper;
import com.fitmind.trade.service.ICartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 购物车服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl extends ServiceImpl<TradeCartMapper, TradeCart> implements ICartService {

    private final TradeProperties tradeProperties;
    private final CourseQueryHelper courseQueryHelper;
    private final TradeOrderDetailMapper orderDetailMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addCourse(Long courseId) {
        Long userId = UserContext.getUser();

        // 1. 课程必须存在且已上架（同时拿到课程名/封面/价格做快照）
        List<CourseSimpleDTO> courses = courseQueryHelper.queryOnShelfCourses(List.of(courseId));
        CourseSimpleDTO course = courses.get(0);

        // 2. 免费课不走购物车。
        //    免费课是"直接报名"，加进购物车也会在结算时被拒 —— 与其让用户
        //    走到结算页才碰壁，不如在入口就告诉他该点哪里。
        if (CourseQueryHelper.isFree(course)) {
            throw new BizIllegalException(
                    String.format(TradeErrorInfo.Msg.ORDER_COURSE_FREE, course.getName()));
        }

        // 3. 已经买过的课不该再进购物车 —— 否则用户加进去、点结算，才发现买不了
        List<Long> bought = orderDetailMapper.queryBoughtCourseIds(
                userId, List.of(courseId), TradeConstants.DETAIL_PAYED);
        if (!bought.isEmpty()) {
            throw new BizIllegalException(
                    String.format(TradeErrorInfo.Msg.CART_COURSE_ALREADY_BOUGHT, course.getName()));
        }

        // 4. 容量上限
        int max = tradeProperties.getCart().getMaxCourseAmount();
        long count = count(new LambdaQueryWrapper<TradeCart>().eq(TradeCart::getUserId, userId));
        if (count >= max) {
            throw new BizIllegalException(String.format(TradeErrorInfo.Msg.CART_FULL, max));
        }

        // 5. 落库。
        //    ⚠️ 重复加入不靠"先查一次再插"——那中间有窗口期。直接插，
        //       让 uk_user_course 唯一索引兜底，冲突了再翻译成友好提示。
        try {
            save(new TradeCart()
                    .setUserId(userId)
                    .setCourseId(courseId)
                    .setCourseName(course.getName())
                    .setCoverUrl(course.getCoverUrl())
                    .setPrice(course.getPrice()));
        } catch (DuplicateKeyException e) {
            throw new BizIllegalException(
                    String.format(TradeErrorInfo.Msg.CART_COURSE_DUPLICATE, course.getName()));
        }
        log.info("加入购物车：userId={}, courseId={}", userId, courseId);
    }

    @Override
    public List<CartVO> queryMyCarts() {
        Long userId = UserContext.getUser();
        List<TradeCart> carts = list(new LambdaQueryWrapper<TradeCart>()
                .eq(TradeCart::getUserId, userId)
                .orderByDesc(TradeCart::getCreateTime));
        if (CollectionUtils.isEmpty(carts)) {
            return new ArrayList<>();
        }
        List<Long> courseIds = carts.stream().map(TradeCart::getCourseId).collect(Collectors.toList());

        // 批量查课程最新信息。
        // ★ 课程服务不可用时**降级**成"用快照展示 + 标为待刷新"，而不是整个列表 500 ——
        //   购物车是"随时想看就能看"的页面，不该因为下游抖动变成白屏。
        //   （但下单接口不降级：算钱必须用准确价格，见 OrderServiceImpl）
        Map<Long, CourseSimpleDTO> courseMap;
        boolean courseServiceOk = true;
        try {
            courseMap = courseQueryHelper.batchQuery(courseIds);
        } catch (Exception e) {
            log.warn("课程服务不可用，购物车降级用快照展示：userId={}", userId);
            courseMap = Map.of();
            courseServiceOk = false;
        }

        Set<Long> boughtIds = new HashSet<>(orderDetailMapper.queryBoughtCourseIds(
                userId, courseIds, TradeConstants.DETAIL_PAYED));

        List<CartVO> result = new ArrayList<>(carts.size());
        for (TradeCart cart : carts) {
            result.add(toVO(cart, courseMap.get(cart.getCourseId()), courseServiceOk,
                    boughtIds.contains(cart.getCourseId())));
        }
        return result;
    }

    @Override
    public void removeCourse(Long courseId) {
        // 幂等：不在购物车里也不报错（用户可能连点两次，或者换个设备已经删过了）
        remove(new LambdaQueryWrapper<TradeCart>()
                .eq(TradeCart::getUserId, UserContext.getUser())
                .eq(TradeCart::getCourseId, courseId));
        log.info("移出购物车：userId={}, courseId={}", UserContext.getUser(), courseId);
    }

    @Override
    public void clear() {
        remove(new LambdaQueryWrapper<TradeCart>().eq(TradeCart::getUserId, UserContext.getUser()));
    }

    /** 组装购物车条目：课程信息可用就用最新的，不可用就退回快照并标记失效 */
    private CartVO toVO(TradeCart cart, CourseSimpleDTO course, boolean courseServiceOk, boolean bought) {
        CartVO vo = new CartVO()
                .setCourseId(cart.getCourseId())
                .setAddedPrice(cart.getPrice())
                .setBought(bought);
        if (!courseServiceOk) {
            // 降级：课程信息取不到，用加入时的快照撑着，并明确告诉前端"这个价格可能不准"
            log.debug("购物车条目降级展示（课程服务不可用）：courseId={}", cart.getCourseId());
            return vo.setCourseName(cart.getCourseName())
                    .setCoverUrl(cart.getCoverUrl())
                    .setPrice(cart.getPrice())
                    .setInvalid(true)
                    .setInvalidReason("课程信息暂时获取不到，请稍后刷新");
        }
        if (course == null) {
            // 服务正常但查不到 → 课程真的没了
            return vo.setCourseName(cart.getCourseName())
                    .setCoverUrl(cart.getCoverUrl())
                    .setPrice(cart.getPrice())
                    .setInvalid(true)
                    .setInvalidReason("课程已下架或被删除");
        }
        boolean onShelf = Objects.equals(course.getStatus(), TradeConstants.COURSE_STATUS_SHELF);
        return vo.setCourseName(course.getName())
                .setCoverUrl(course.getCoverUrl())
                .setPrice(course.getPrice())
                .setInvalid(!onShelf)
                .setInvalidReason(onShelf ? null : "课程已下架");
    }
}
