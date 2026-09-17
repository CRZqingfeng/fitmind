package com.fitmind.aigc.limit;

/**
 * 一次"额度申请"的结果。
 *
 * @param allowed  是否放行
 * @param reason   放行时是 {@code OK}；拒绝时是 {@code CHAT_LIMIT} / {@code TOKEN_LIMIT} /
 *                 {@code CONCURRENCY}（见 {@code ChatQuotaLimiter}）
 * @param usedToday 今日已用轮数（放行后是"含本轮"的值）
 * @param limit    今日轮数上限（拒绝时告诉用户"上限是多少"，他才知道什么时候能再用）
 */
public record QuotaResult(boolean allowed, String reason, int usedToday, int limit) {

    public static final String OK = "OK";
    public static final String CHAT_LIMIT = "CHAT_LIMIT";
    public static final String TOKEN_LIMIT = "TOKEN_LIMIT";
    public static final String CONCURRENCY = "CONCURRENCY";

    public static QuotaResult allow(int usedToday, int limit) {
        return new QuotaResult(true, OK, usedToday, limit);
    }

    public static QuotaResult deny(String reason, int usedToday, int limit) {
        return new QuotaResult(false, reason, usedToday, limit);
    }

    public boolean limited() {
        return !allowed;
    }

    public int remaining() {
        return Math.max(0, limit - usedToday);
    }
}
