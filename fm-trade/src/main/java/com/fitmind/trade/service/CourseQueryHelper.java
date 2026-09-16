package com.fitmind.trade.service;

import com.fitmind.api.client.course.CourseClient;
import com.fitmind.api.dto.course.CourseSimpleDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import com.fitmind.trade.constants.TradeConstants;
import com.fitmind.trade.constants.TradeErrorInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 课程信息查询助手（购物车与订单共用）。
 *
 * <p>抽出来的理由很实际：购物车和下单都要做同一件事 ——
 * <b>批量查课程 + 判断上架 + 区分"课程不存在"和"课程服务挂了"</b>。
 * 写两遍不仅冗长，更麻烦的是两边对"不可用"的处理容易不一致。</p>
 *
 * <p>这里用 {@code /courses/internal/simple} 这个<b>批量</b>契约，
 * 而不是逐个查 {@code /full}：结算 8 门课就是 1 次 Feign 调用 vs 8 次。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseQueryHelper {

    private final CourseClient courseClient;

    /**
     * 批量查询课程，<b>课程服务不可用时抛业务异常</b>（而不是返回空）。
     *
     * <p>为什么不悄悄降级：下单要算钱，算不出准确金额就不该让流程继续。
     * 宁可告诉用户"稍后重试"，也不能拿一个猜的价格去下单。</p>
     *
     * @return courseId -> 课程信息；<b>可能少于请求数量</b>（不存在的课程不会出现在结果里）
     */
    public Map<Long, CourseSimpleDTO> batchQuery(List<Long> courseIds) {
        if (CollectionUtils.isEmpty(courseIds)) {
            return new HashMap<>();
        }
        List<CourseSimpleDTO> list;
        try {
            list = courseClient.queryCourseSimpleList(courseIds);
        } catch (Exception e) {
            log.error("调用课程服务失败，courseIds={}", courseIds, e);
            throw new BizIllegalException(TradeErrorInfo.Msg.ORDER_COURSE_SERVICE_UNAVAILABLE);
        }
        // ⚠️ 降级的 CourseClientFallback 返回的是 null —— 这就是"服务不可用"的信号。
        //    如果它返回空 list，我们将无法区分"服务挂了"和"课程都不存在"。
        if (list == null) {
            throw new BizIllegalException(TradeErrorInfo.Msg.ORDER_COURSE_SERVICE_UNAVAILABLE);
        }
        return list.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(CourseSimpleDTO::getId, c -> c, (a, b) -> a));
    }

    /**
     * 查一批课程，并校验它们<b>都存在且已上架</b>。
     *
     * @return 与入参<b>顺序一致</b>的课程列表（这样明细的顺序 = 用户选择的顺序）
     */
    public List<CourseSimpleDTO> queryOnShelfCourses(List<Long> courseIds) {
        // 去重后再查：用户在购物车里不会重复选同一门课，但接口参数不保证
        List<Long> distinctIds = courseIds.stream().distinct().collect(Collectors.toList());
        Map<Long, CourseSimpleDTO> map = batchQuery(distinctIds);

        List<CourseSimpleDTO> result = new ArrayList<>(distinctIds.size());
        for (Long id : distinctIds) {
            CourseSimpleDTO course = map.get(id);
            if (course == null) {
                throw new BizIllegalException(TradeErrorInfo.Msg.CART_COURSE_NOT_EXISTS);
            }
            if (!Objects.equals(course.getStatus(), TradeConstants.COURSE_STATUS_SHELF)) {
                throw new BizIllegalException(
                        String.format(TradeErrorInfo.Msg.ORDER_COURSE_NOT_ON_SHELF, course.getName()));
            }
            result.add(course);
        }
        return result;
    }

    /** 课程是否免费（1-免费 2-付费） */
    public static boolean isFree(CourseSimpleDTO course) {
        return course.getFree() != null && course.getFree() == 1;
    }
}
