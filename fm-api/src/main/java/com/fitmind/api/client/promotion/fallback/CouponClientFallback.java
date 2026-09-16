package com.fitmind.api.client.promotion.fallback;

import com.fitmind.api.client.promotion.CouponClient;
import com.fitmind.api.constants.ApiErrorInfo;
import com.fitmind.api.dto.promotion.CouponDiscountDTO;
import com.fitmind.common.exceptions.BizIllegalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@link CouponClient} 的降级工厂。
 *
 * <p>★ 求优惠方案失败时返回 <b>null</b>（而不是空列表）——
 * 调用方要能区分"没有可用券"和"促销服务挂了"：</p>
 * <ul>
 *   <li>空列表 = 确实没券，按原价下单即可</li>
 *   <li>null = 服务不可用，<b>应该中断下单</b>（否则用户可能在有券的情况下按原价付了钱）</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnClass(FallbackFactory.class)
public class CouponClientFallback implements FallbackFactory<CouponClient> {

    @Override
    public CouponClient create(Throwable cause) {
        return new CouponClient() {
            @Override
            public List<CouponDiscountDTO> findSolution(Long userId, List<Long> courseIds, List<Integer> prices) {
                log.error("促销服务不可用，无法计算优惠方案。userId={}，原因：{}", userId, cause.getMessage());
                return null;
            }

            @Override
            public CouponDiscountDTO queryDetail(Long userId, List<Long> courseIds,
                                                 List<Integer> prices, List<Long> userCouponIds) {
                log.error("促销服务不可用，无法按券计算优惠。userId={}，原因：{}", userId, cause.getMessage());
                // ★ 这里<b>抛异常</b>而不是返回 null：调用方要能区分
                //   "券不可用"（返回 null，用户改选券即可）和"促销服务挂了"（该提示重试）
                throw new BizIllegalException(ApiErrorInfo.Msg.PROMOTION_SERVICE_UNAVAILABLE);
            }

            @Override
            public void writeOff(Map<String, Object> body) {
                log.error("促销服务不可用，核销券失败：{}", cause.getMessage());
            }

            @Override
            public void release(List<Long> userCouponIds) {
                log.error("促销服务不可用，释放券失败：{}", cause.getMessage());
            }
        };
    }
}
