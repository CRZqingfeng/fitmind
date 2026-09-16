package com.fitmind.trade.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fitmind.trade.domain.po.TradeCart;
import com.fitmind.trade.domain.vo.CartVO;

import java.util.List;

/**
 * 购物车服务。
 */
public interface ICartService extends IService<TradeCart> {

    /** 加入购物车（重复加入 / 已购 / 超上限都会被拒） */
    void addCourse(Long courseId);

    /**
     * 查询我的购物车。
     *
     * <p>返回的是课程服务的<b>最新价</b>，不是加入时的快照价 ——
     * 购物车要展示现价，否则用户会拿着过期价格去预期。</p>
     */
    List<CartVO> queryMyCarts();

    /** 移出购物车（按课程id，幂等：不在购物车里也不报错） */
    void removeCourse(Long courseId);

    /** 清空购物车 */
    void clear();
}
