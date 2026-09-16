package com.fitmind.aigc.constants;

/**
 * AI 模块错误提示。
 *
 * <p>全项目统一这个结构（纯常量，不用枚举）：错误信息是给人看的文案，
 * 用常量类的好处是<b>能被 IDE 全局检索</b> —— 想改一句提示，搜到一处就够。</p>
 */
public final class AigcErrorInfo {

    private AigcErrorInfo() {
    }

    public static final class Msg {

        private Msg() {
        }

        // ==================== 会话 ====================
        public static final String SESSION_NOT_EXISTS = "会话不存在";
        /**
         * 会话不属于当前用户。
         *
         * <p>★ 注意文案：**不区分"不存在"和"不是你的"**，
         * 统一说"会话不存在"。如果分开说，等于给攻击者一个探测接口：
         * 报"不是你的"就说明这个 sessionId 真实存在，可以继续猜别的。
         * 这也是为什么查会话时 WHERE 条件里必须同时有 user_id（见建表注释）。</p>
         */
        public static final String SESSION_NOT_MINE = "会话不存在";

        // ==================== 对话 ====================
        public static final String CONTENT_BLANK = "说点什么吧";
        public static final String CONTENT_TOO_LONG = "单次提问不超过 1000 字";
    }
}
