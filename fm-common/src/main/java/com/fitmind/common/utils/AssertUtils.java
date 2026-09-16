package com.fitmind.common.utils;

import com.fitmind.common.exceptions.BadRequestException;
import com.fitmind.common.exceptions.BizIllegalException;

/**
 * 业务断言工具。
 *
 * <p>把「if 判断 + 抛异常」压缩成一行，让 Service 层代码更聚焦业务。</p>
 *
 * <pre>
 * // 等价于 if (user == null) throw new BadRequestException("用户不存在");
 * AssertUtils.isNotNull(user, "用户不存在");
 * </pre>
 */
public class AssertUtils {

    private AssertUtils() {
    }

    /** 断言为真，否则抛参数异常 */
    public static void isTrue(boolean expression, String message) {
        if (!expression) {
            throw new BadRequestException(message);
        }
    }

    /** 断言为真，否则抛业务异常 */
    public static void isBizTrue(boolean expression, String message) {
        if (!expression) {
            throw new BizIllegalException(message);
        }
    }

    /** 断言非空，否则抛参数异常 */
    public static void isNotNull(Object obj, String message) {
        if (obj == null) {
            throw new BadRequestException(message);
        }
    }

    /** 断言为 null，否则抛参数异常 */
    public static void isNull(Object obj, String message) {
        if (obj != null) {
            throw new BadRequestException(message);
        }
    }
}
