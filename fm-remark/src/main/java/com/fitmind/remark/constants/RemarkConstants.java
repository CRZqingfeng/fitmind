package com.fitmind.remark.constants;

/**
 * 点赞服务常量。
 *
 * <p>⚠️ Redis key 一律带 {@code bizType}：常见实现只用 {@code bizId} 做 key，
 * 建立在「雪花 id 全局唯一」的前提上。我们的主键是自增的，
 * 回答 id=5 与笔记 id=5 会撞进同一个 key，所以必须用类型隔离。</p>
 */
public interface RemarkConstants {

    /** 判重 + 计数的 Set key 前缀，实际 key = like:set:{bizType}:{bizId} */
    String LIKES_SET_KEY_PREFIX = "like:set:";

    /** 待同步的脏标 Set key 前缀，实际 key = like:dirty:{bizType} */
    String LIKES_DIRTY_KEY_PREFIX = "like:dirty:";

    static String likesSetKey(String bizType, Long bizId) {
        return LIKES_SET_KEY_PREFIX + bizType + ":" + bizId;
    }

    static String likesDirtyKey(String bizType) {
        return LIKES_DIRTY_KEY_PREFIX + bizType;
    }
}
