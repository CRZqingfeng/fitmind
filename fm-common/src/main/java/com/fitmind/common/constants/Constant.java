package com.fitmind.common.constants;

/**
 * 全局常量。
 */
public class Constant {

    private Constant() {
    }

    /** 请求链路 id 的请求头 */
    public static final String REQUEST_ID_HEADER = "requestId";

    /**
     * 标记请求来源的请求头。
     *
     * <p>这是本项目的关键设计之一：同一个接口，被「外部（经网关）」调用时返回值要包成
     * {@code R{code,msg,data}}；被「服务间 Feign 调用」时则要返回原始对象，
     * 否则 Feign 反序列化会失败。于是用这个请求头来区分来源。</p>
     */
    public static final String REQUEST_FROM_HEADER = "request-from";

    /** 请求来源——网关（外部请求） */
    public static final String GATEWAY_ORIGIN_NAME = "gateway";

    /** 请求来源——服务间内部调用 */
    public static final String FEIGN_ORIGIN_NAME = "feign";

    /** 线程上下文标记：请求来源 */
    public static final String REQUEST_ORIGIN_MARK = "request-origin";

    // ==================== 通用数据字段名 ====================
    /** 主键字段 */
    public static final String DATA_FIELD_NAME_ID = "id";
    /** 创建时间字段（数据库下划线命名） */
    public static final String DATA_FIELD_NAME_CREATE_TIME = "create_time";
    /** 更新时间字段（数据库下划线命名） */
    public static final String DATA_FIELD_NAME_UPDATE_TIME = "update_time";
    /** 创建人字段 */
    public static final String DATA_FIELD_NAME_CREATER = "creater";
    /** 更新人字段 */
    public static final String DATA_FIELD_NAME_UPDATER = "updater";
}
