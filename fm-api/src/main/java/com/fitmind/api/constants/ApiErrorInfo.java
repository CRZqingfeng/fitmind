package com.fitmind.api.constants;

/**
 * 跨服务调用相关的提示文案。
 *
 * <p>为什么放在 <b>fm-api</b>：这些文案是"调用方在下游不可用时该说什么"，
 * 属于<b>契约的一部分</b>。放在这里，各个调用方（fm-trade 调促销、fm-trade 调支付…）
 * 用的是同一句话，不会出现"同一个故障在不同接口里提示不一样"。</p>
 */
public class ApiErrorInfo {

    private ApiErrorInfo() {
    }

    public static class Msg {

        private Msg() {
        }

        /** 促销服务不可用 */
        public static final String PROMOTION_SERVICE_UNAVAILABLE = "促销服务暂时不可用，请稍后重试";
        /** 支付服务不可用 */
        public static final String PAY_SERVICE_UNAVAILABLE = "支付服务暂时不可用，请稍后重试";
        /** 课程服务不可用 */
        public static final String COURSE_SERVICE_UNAVAILABLE = "课程信息暂时不可用，请稍后重试";
        /** 下游服务不可用（通用兜底） */
        public static final String DOWNSTREAM_UNAVAILABLE = "服务暂时不可用，请稍后重试";
    }
}
