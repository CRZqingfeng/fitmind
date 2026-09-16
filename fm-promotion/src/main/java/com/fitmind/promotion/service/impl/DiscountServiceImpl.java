package com.fitmind.promotion.service.impl;

import com.fitmind.promotion.constants.PromotionConstants;
import com.fitmind.promotion.domain.dto.CouponDiscountDTO;
import com.fitmind.promotion.domain.dto.OrderCourseDTO;
import com.fitmind.promotion.domain.po.Coupon;
import com.fitmind.promotion.mapper.CouponScopeMapper;
import com.fitmind.promotion.mapper.UserCouponMapper;
import com.fitmind.promotion.service.IDiscountService;
import com.fitmind.promotion.strategy.discount.Discount;
import com.fitmind.promotion.strategy.discount.DiscountStrategy;
import com.fitmind.promotion.utils.PermuteUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ★★★ 叠加推荐（课件里叫「MapReduce 思想」）。
 *
 * <h3>问题是什么</h3>
 * <p>用户手里有 5 张券，买 3 门课。哪些券能一起用？怎么组合最划算？
 * 这不是"挑一张最优惠的券"那么简单 —— 券之间有相互作用：</p>
 * <ul>
 *   <li>先扣满减券之后，剩余金额可能就不够另一张券的门槛了</li>
 *   <li>券有<b>限定范围</b>（这张券只能用于某几门课），所以顺序会影响结果</li>
 * </ul>
 * <p>所以要穷举"券的排列"，逐个算，再挑最优。</p>
 *
 * <h3>所谓 MapReduce</h3>
 * <ul>
 *   <li><b>Map</b>：把每个方案的计算丢进线程池并行算
 *       （{@code CompletableFuture.supplyAsync}）</li>
 *   <li><b>Reduce</b>：用 {@code CountDownLatch} 收齐结果，
 *       再用两个 Map 筛出最优（同券组合取优惠最大的 + 同优惠取用券最少的）</li>
 * </ul>
 *
 * <h3>★ 工程上的取舍（比算法本身更重要）</h3>
 * <ol>
 *   <li><b>券数要封顶</b>：N 张券的全排列是 N! 阶乘级增长，
 *       10 张券就是 360 万个方案。所以超过 {@code max-permute-coupons}
 *       就只算单券方案 —— <b>宁可少算，也不能把接口拖垮</b></li>
 *   <li><b>要设超时</b>：{@code latch.await(timeout)} 到点就返回已算出的部分。
 *       推荐结果是"锦上添花"，不能因为它让下单卡住</li>
 *   <li><b>失败不影响主流程</b>：单个方案算错不影响其他方案</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscountServiceImpl implements IDiscountService {

    private final UserCouponMapper userCouponMapper;
    private final CouponScopeMapper couponScopeMapper;
    private final Executor discountSolutionExecutor;

    @Value("${promotion.solution-timeout-ms:1000}")
    private long solutionTimeoutMs;

    @Value("${promotion.max-permute-coupons:6}")
    private int maxPermuteCoupons;

    @Override
    public List<CouponDiscountDTO> findDiscountSolution(List<OrderCourseDTO> orderCourses, Long userId) {
        if (CollectionUtils.isEmpty(orderCourses) || userId == null) {
            return List.of();
        }
        // 1. 查我所有未使用且未过期的券
        List<Coupon> coupons = userCouponMapper.queryMyAvailableCoupons(userId);
        if (CollectionUtils.isEmpty(coupons)) {
            return List.of();
        }

        // 2. 初筛：按订单总价过滤掉门槛都够不上的券
        int totalAmount = orderCourses.stream().mapToInt(OrderCourseDTO::getPrice).sum();
        List<Coupon> available = coupons.stream()
                .filter(c -> DiscountStrategy.getDiscount(c.getDiscountType()).canUse(totalAmount, c))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(available)) {
            return List.of();
        }

        // 3. 细筛：算出每张券**能用于哪几门课**（限定范围）
        Map<Coupon, List<OrderCourseDTO>> couponMap = findAvailableCoupon(available, orderCourses);
        if (CollectionUtils.isEmpty(couponMap)) {
            return List.of();
        }

        // 4. 排列组合出所有方案
        List<Coupon> usable = new ArrayList<>(couponMap.keySet());
        List<List<Coupon>> solutions = new ArrayList<>();
        if (usable.size() <= maxPermuteCoupons) {
            solutions.addAll(PermuteUtil.permute(usable));
        }
        // 单券方案总是要加（券数超限时至少还有这些）
        for (Coupon c : usable) {
            solutions.add(List.of(c));
        }

        // 5. ★ Map：并行计算每个方案
        List<CouponDiscountDTO> results = Collections.synchronizedList(new ArrayList<>(solutions.size()));
        CountDownLatch latch = new CountDownLatch(solutions.size());
        for (List<Coupon> solution : solutions) {
            CompletableFuture
                    .supplyAsync(() -> calculateSolutionDiscount(couponMap, orderCourses, solution),
                            discountSolutionExecutor)
                    .thenAccept(dto -> {
                        if (dto != null) {
                            results.add(dto);
                        }
                        latch.countDown();
                    })
                    .exceptionally(e -> {
                        log.warn("优惠方案计算失败：{}", e.getMessage());
                        latch.countDown();
                        return null;
                    });
        }
        try {
            // ★ 超时就返回已算出的部分 —— 推荐是"锦上添花"，不能拖住下单
            latch.await(solutionTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            log.error("优惠方案计算被中断：{}", e.getMessage());
            Thread.currentThread().interrupt();
        }

        // 6. ★ Reduce：挑最优
        return findBestSolution(results);
    }

    @Override
    public CouponDiscountDTO queryDiscountDetail(List<Long> userCouponIds,
                                                 List<OrderCourseDTO> orderCourses, Long userId) {
        if (CollectionUtils.isEmpty(userCouponIds) || CollectionUtils.isEmpty(orderCourses)) {
            return null;
        }
        List<Coupon> coupons = userCouponMapper.queryByUserCouponIds(userCouponIds, userId);
        if (CollectionUtils.isEmpty(coupons)) {
            return null;
        }
        Map<Coupon, List<OrderCourseDTO>> couponMap = findAvailableCoupon(coupons, orderCourses);
        if (CollectionUtils.isEmpty(couponMap)) {
            return null;
        }
        // ★★ 这里<b>不能</b>直接拿 couponMap.keySet() 的顺序去算！
        //   券的**使用顺序会改变结果**（先扣掉的金额会让后面那张券够不上门槛），
        //   而 keySet 的顺序取决于 HashMap 的哈希分布 —— 随券 id 变化，
        //   表现为"同样勾选 4 张券，有时能减 4 张、有时只减 3 张"，且随机复现。
        //   （本项目实测：回归里偶发 3/4 核销失败，单独跑又通过，就是这里。）
        //   正确做法：在这个"用户选定的券集合"内穷举排列，取最优 ——
        //   与推荐接口（findDiscountSolution）保持同一套算法。
        return bestOf(couponMap, orderCourses);
    }

    /**
     * 在<b>给定的券集合</b>内穷举排列，返回最优方案。
     *
     * <p>抽出来给「推荐」和「按用户选券算」两个入口共用 ——
     * 两处都必须是"穷举取最优"，否则同一个券集合会算出不同结果。</p>
     */
    private CouponDiscountDTO bestOf(Map<Coupon, List<OrderCourseDTO>> couponMap,
                                     List<OrderCourseDTO> orderCourses) {
        List<Coupon> usable = new ArrayList<>(couponMap.keySet());
        List<List<Coupon>> solutions = new ArrayList<>();
        if (usable.size() <= maxPermuteCoupons) {
            solutions.addAll(PermuteUtil.permute(usable));
        }
        for (Coupon c : usable) {
            solutions.add(List.of(c));
        }
        if (solutions.isEmpty()) {
            return null;
        }
        List<CouponDiscountDTO> results = Collections.synchronizedList(new ArrayList<>(solutions.size()));
        CountDownLatch latch = new CountDownLatch(solutions.size());
        for (List<Coupon> solution : solutions) {
            CompletableFuture
                    .supplyAsync(() -> calculateSolutionDiscount(couponMap, orderCourses, solution),
                            discountSolutionExecutor)
                    .thenAccept(dto -> {
                        if (dto != null) {
                            results.add(dto);
                        }
                        latch.countDown();
                    })
                    .exceptionally(e -> {
                        log.warn("优惠方案计算失败：{}", e.getMessage());
                        latch.countDown();
                        return null;
                    });
        }
        try {
            latch.await(solutionTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            log.error("优惠方案计算被中断：{}", e.getMessage());
            Thread.currentThread().interrupt();
        }
        List<CouponDiscountDTO> best = findBestSolution(results);
        return best.isEmpty() ? null : best.get(0);
    }

    /**
     * 细筛：为每张券算出它能用于哪几门课。
     *
     * <p>不限定范围（specific=0）→ 所有课都能用；
     * 限定了 → 只能用于范围内的课，且这些课的合计价要达到门槛。</p>
     */
    private Map<Coupon, List<OrderCourseDTO>> findAvailableCoupon(List<Coupon> coupons,
                                                                  List<OrderCourseDTO> orderCourses) {
        Map<Coupon, List<OrderCourseDTO>> map = new HashMap<>();
        // 一次性查出这些券的限定范围，避免每张券查一次
        List<Long> couponIds = coupons.stream().map(Coupon::getId).collect(Collectors.toList());
        Map<Long, Set<Long>> scopeMap = couponScopeMapper
                .queryScopes(PromotionConstants.SCOPE_COURSE, couponIds).stream()
                .collect(Collectors.groupingBy(CouponScopeMapper.ScopeRow::getCouponId,
                        Collectors.mapping(CouponScopeMapper.ScopeRow::getBizId, Collectors.toSet())));

        for (Coupon coupon : coupons) {
            List<OrderCourseDTO> availableCourses;
            if (!Integer.valueOf(1).equals(coupon.getSpecific())) {
                availableCourses = new ArrayList<>(orderCourses);
            } else {
                Set<Long> scoped = scopeMap.getOrDefault(coupon.getId(), Set.of());
                availableCourses = orderCourses.stream()
                        .filter(c -> scoped.contains(c.getId()))
                        .collect(Collectors.toList());
            }
            if (CollectionUtils.isEmpty(availableCourses)) {
                continue;
            }
            int amount = availableCourses.stream().mapToInt(OrderCourseDTO::getPrice).sum();
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if (!discount.canUse(amount, coupon)) {
                continue;
            }
            map.put(coupon, availableCourses);
        }
        return map;
    }

    /**
     * 算一个方案能优惠多少。
     *
     * <p>按顺序处理每张券：每次用"剩余可扣金额"判断门槛并计算 ——
     * 这就是<b>顺序会影响结果</b>的原因，也是为什么要穷举排列。</p>
     */
    private CouponDiscountDTO calculateSolutionDiscount(Map<Coupon, List<OrderCourseDTO>> couponMap,
                                                        List<OrderCourseDTO> courses,
                                                        List<Coupon> solution) {
        CouponDiscountDTO dto = new CouponDiscountDTO();
        // 每门课已分摊到的优惠
        Map<Long, Integer> detailMap = courses.stream()
                .collect(Collectors.toMap(OrderCourseDTO::getId, c -> 0));
        List<Long> ids = new ArrayList<>();
        List<String> rules = new ArrayList<>();
        int totalDiscount = 0;

        for (Coupon coupon : solution) {
            List<OrderCourseDTO> availableCourses = couponMap.get(coupon);
            if (CollectionUtils.isEmpty(availableCourses)) {
                continue;
            }
            // 剩余可扣金额 = 原价 - 已分摊的优惠
            int remaining = availableCourses.stream()
                    .mapToInt(oc -> oc.getPrice() - detailMap.get(oc.getId()))
                    .sum();
            Discount discount = DiscountStrategy.getDiscount(coupon.getDiscountType());
            if (!discount.canUse(remaining, coupon)) {
                continue;
            }
            int d = discount.calculateDiscount(remaining, coupon);
            if (d <= 0) {
                continue;
            }
            totalDiscount += d;
            // ★ 分摊规则：按各课剩余金额的比例摊到每门课上。
            //   这样"每门课实付多少"加起来正好等于订单实付，不会出现对不上的情况
            distribute(availableCourses, detailMap, d, remaining);
            // ⚠️ 这里必须是**用户券id**（user_coupon.id），不是券模板id（coupon.id）！
            //    下游要拿它去记订单的 coupon_ids、并在支付后按它核销；
            //    用模板 id 的话核销 UPDATE 会永远影响 0 行（不报错，券一直停在未使用）。
            Long ucId = coupon.getUserCouponId();
            if (ucId == null) {
                // 说明 SQL 没回填上 —— 这是必须暴露的问题：回退用模板 id 会让核销静默失败
                log.error("券缺少 userCouponId（核销会失败）：couponTemplateId={}, name={}",
                        coupon.getId(), coupon.getName());
            }
            ids.add(ucId != null ? ucId : coupon.getId());
            rules.add(discount.getRule(coupon));
        }
        if (totalDiscount <= 0) {
            return null;
        }
        return dto.setIds(ids).setRules(rules)
                .setDiscountAmount(totalDiscount).setDiscountDetail(detailMap);
    }

    /** 把一笔优惠按各课剩余金额的比例摊到每门课（最后一门兜底，保证总额分完） */
    private void distribute(List<OrderCourseDTO> courses, Map<Long, Integer> detailMap,
                            int discount, int remaining) {
        int assigned = 0;
        for (int i = 0; i < courses.size(); i++) {
            OrderCourseDTO c = courses.get(i);
            int left = c.getPrice() - detailMap.get(c.getId());
            int part;
            if (i == courses.size() - 1) {
                part = discount - assigned;         // 最后一门兜底，避免四舍五入丢/多分
            } else {
                part = (int) ((long) discount * left / remaining);
            }
            detailMap.merge(c.getId(), part, Integer::sum);
            assigned += part;
        }
    }

    /**
     * ★ Reduce：从所有方案里挑最优。
     *
     * <p>两个维度同时最优才算好：</p>
     * <ul>
     *   <li><b>同样的券组合</b> → 取优惠金额最大的（券的使用顺序不同结果不同）</li>
     *   <li><b>同样的优惠金额</b> → 取用券最少的（能省一张券是一张，留着下次用）</li>
     * </ul>
     * <p>两个 Map 各筛一遍再取交集，就是同时满足两者的方案。</p>
     */
    private List<CouponDiscountDTO> findBestSolution(List<CouponDiscountDTO> list) {
        if (CollectionUtils.isEmpty(list)) {
            return List.of();
        }
        Map<String, CouponDiscountDTO> moreDiscountMap = new HashMap<>();
        Map<Integer, CouponDiscountDTO> lessCouponMap = new HashMap<>();

        for (CouponDiscountDTO solution : list) {
            String ids = solution.getIds().stream()
                    .sorted().map(String::valueOf).collect(Collectors.joining(","));

            // 1) 同样的券组合，优惠金额小的直接跳过
            CouponDiscountDTO best = moreDiscountMap.get(ids);
            if (best != null && best.getDiscountAmount() >= solution.getDiscountAmount()) {
                continue;
            }
            // 2) 同样的优惠金额，用券多的跳过
            best = lessCouponMap.get(solution.getDiscountAmount());
            if (solution.getIds().size() > 1 && best != null
                    && best.getIds().size() <= solution.getIds().size()) {
                continue;
            }
            moreDiscountMap.put(ids, solution);
            lessCouponMap.put(solution.getDiscountAmount(), solution);
        }

        // 3) 取交集（同时在两个维度上胜出的）
        Collection<CouponDiscountDTO> bestSolutions = moreDiscountMap.values().stream()
                .filter(s -> lessCouponMap.get(s.getDiscountAmount()) == s)
                .collect(Collectors.toList());

        // 4) 按优惠金额降序 —— 第一个就是最划算的
        return bestSolutions.stream()
                .sorted(Comparator.comparingInt(CouponDiscountDTO::getDiscountAmount).reversed())
                .collect(Collectors.toList());
    }
}
