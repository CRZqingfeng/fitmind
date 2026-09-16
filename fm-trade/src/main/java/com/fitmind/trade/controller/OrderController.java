package com.fitmind.trade.controller;

import com.fitmind.common.domain.dto.PageDTO;
import com.fitmind.trade.domain.dto.PlaceOrderDTO;
import com.fitmind.trade.domain.query.OrderPageQuery;
import com.fitmind.trade.domain.vo.OrderVO;
import com.fitmind.trade.domain.vo.PayUrlVO;
import com.fitmind.trade.domain.vo.PlaceOrderResultVO;
import com.fitmind.trade.domain.vo.PrePlaceOrderVO;
import com.fitmind.trade.service.IOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单接口。
 *
 * <p>下单是<b>两步</b>：先 {@code GET /orders/prePlace} 拿订单 id 与金额预览，
 * 再 {@code POST /orders} 提交。多一步换来的是下单接口天然幂等。</p>
 */
@Validated
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Tag(name = "订单接口")
public class OrderController {

    private final IOrderService orderService;

    @Operation(summary = "预下单：生成订单id + 金额预览（不落库）")
    @Parameters(@Parameter(name = "courseIds", description = "课程id列表，逗号分隔", example = "1,2"))
    @GetMapping("/prePlace")
    public PrePlaceOrderVO prePlace(@RequestParam("courseIds") List<Long> courseIds) {
        return orderService.prePlaceOrder(courseIds);
    }

    @Operation(summary = "提交订单")
    @PostMapping
    public PlaceOrderResultVO place(@RequestBody @Valid PlaceOrderDTO dto) {
        return orderService.placeOrder(dto);
    }

    @Operation(summary = "我的订单分页（可按状态筛选）")
    @GetMapping("/page")
    public PageDTO<OrderVO> page(OrderPageQuery query) {
        return orderService.queryMyOrders(query);
    }

    @Operation(summary = "订单详情")
    @Parameters(@Parameter(name = "id", description = "订单id"))
    @GetMapping("/{id}")
    public OrderVO detail(@PathVariable("id") Long id) {
        return orderService.queryOrderDetail(id);
    }

    @Operation(summary = "为订单发起支付（金额由服务端从订单取，前端传不了）")
    @Parameters({
            @Parameter(name = "id", description = "订单id"),
            @Parameter(name = "payChannelCode", description = "支付渠道码，默认 mockPay")
    })
    @PostMapping("/{id}/pay")
    public PayUrlVO applyPay(@PathVariable("id") Long id,
                             @RequestParam(value = "payChannelCode", required = false, defaultValue = "mockPay") String payChannelCode) {
        return orderService.applyPay(id, payChannelCode);
    }

    @Operation(summary = "取消订单（只有待支付的能取消）")
    @Parameters(@Parameter(name = "id", description = "订单id"))
    @PutMapping("/{id}/cancel")
    public void cancel(@PathVariable("id") Long id) {
        orderService.cancelOrder(id);
    }
}
