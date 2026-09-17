package com.fitmind.aigc.tool;

import com.fitmind.common.utils.TokenContext;
import com.fitmind.common.utils.UserContext;

import java.util.function.Supplier;

/**
 * 把「调用者身份」带进工具线程。
 *
 * <p>★★ <b>这个类是 M6-4 实测踩坑的产物，原因很隐蔽。</b></p>
 *
 * <p><b>症状</b>：工具 {@code prePlaceOrder} 调 fm-trade 的 {@code /orders/prePlace}
 * 返回 {@code {"code":4013,"msg":"请先登录"}} —— 而用户在界面上明明是登录状态。</p>
 *
 * <p><b>根因</b>：工具的执行<b>不在 HTTP 请求线程上</b>。
 * {@code ToolCallAdvisor} 用 Reactor 的 {@code boundedElastic} 调度器执行工具
 * （日志里线程名是 {@code boundedElastic-3}），
 * 而用户身份存在 {@code UserContext} 的 <b>ThreadLocal</b> 里 ——
 * <b>ThreadLocal 不跨线程传递</b>，于是工具线程里 {@code UserContext.getUser()} 为 null，
 * 下游的 {@code FeignRelayUserInterceptor} 也就没有 {@code user-info} 头可以透传。</p>
 *
 * <p><b>为什么"查体测档案"没出问题</b>：那份数据是编排层在请求线程上预先取好、
 * 直接传给工具的（见 {@code BodyTools}），工具自己并没有发起跨服务调用 ——
 * <b>问题是否暴露，取决于"这个工具有没有在工具线程里发起需登录的调用"</b>。
 * 这让它看起来很偶发，实际是必然的。</p>
 *
 * <p>★ 这与本项目已记录过的另一条坑同族：「MQ 消费 / 定时任务线程没有用户上下文」。
 * <b>凡是换了线程，就得重新把身份带上。</b>区别是这条更隐蔽 ——
 * 代码看起来就待在一次 HTTP 请求里面。</p>
 *
 * <p><b>为什么不干脆把 {@code /orders/prePlace} 改成免登录的内部接口</b>：
 * 那样 AI 就能替<b>任意用户</b>下单，等于把越权入口开在了交易上。
 * 这里必须保持"像普通用户请求一样被鉴权"，所以是<b>把身份带过去</b>，
 * 而不是<b>把鉴权关掉</b>。</p>
 */
public final class ToolAuthContext {

    private ToolAuthContext() {
    }

    /**
     * 在"带上调用者身份"的上下文里执行一段逻辑。
     *
     * <p>★ 注意 {@code userId} 的来源：它是<b>编排层从 {@code UserContext} 里读到的</b>，
     * 由构造函数传进工具实例。<b>它绝不来自模型</b> ——
     * 工具方法签名里依然没有 {@code userId} 参数，
     * "模型编一个 id 去查别人的数据"在结构上仍然不可能。</p>
     */
    public static <T> T runAs(Long userId, String token, Supplier<T> action) {
        Long previousUser = UserContext.getUser();
        String previousToken = TokenContext.getToken();
        try {
            if (userId != null) {
                UserContext.setUser(userId);
            }
            if (token != null && !token.isBlank()) {
                TokenContext.setToken(token);
            }
            return action.get();
        } finally {
            // ⚠️ 必须还原：这个线程会被 Reactor 复用，不清理就会把身份串给下一个任务
            //   （与 UserInfoInterceptor.afterCompletion 里清理 ThreadLocal 是同一个理由）
            if (previousUser != null) {
                UserContext.setUser(previousUser);
            } else {
                UserContext.removeUser();
            }
            if (previousToken != null && !previousToken.isBlank()) {
                TokenContext.setToken(previousToken);
            } else {
                TokenContext.removeToken();
            }
        }
    }
}
