/**
 * 后端统一响应体
 *
 * 对应 Java 侧 `com.fitmind.common.domain.R`。
 * 所有非 SSE 接口都会被 `WrapperResponseBodyAdvice` 包装成这个结构：
 *   - Controller 返回 R 本身 → 原样输出
 *   - Controller 返回其它对象（含 String）→ 包成 R.ok(body)
 *
 * 所以前端**永远**能按 { code, msg, data } 解包，不用为每个接口写不同逻辑。
 *
 * 注：响应里还会带一个 `success` 布尔字段 —— 那是 Java 侧 `isSuccess()` getter
 * 被 Jackson 序列化出来的。前端**不要用它**，判断成功统一看 `code === 200`。
 */
export interface R<T = unknown> {
  code: number
  msg: string
  data: T
  requestId?: string
}

/** 成功码（对应 R.SUCCESS_CODE），不要写魔法数字 200 */
export const SUCCESS_CODE = 200

/**
 * 需要前端**分支处理**的两个状态
 *
 * ★★ 注意：这是 **HTTP 状态码**，不是 `R.code` 业务码。
 *   后端 `CommonExceptionAdvice` 用 `@ResponseStatus(HttpStatus.UNAUTHORIZED/FORBIDDEN)` 标注，
 *   网关鉴权失败也是直接 `response.setStatusCode(401)`。
 *   因此这两类响应走 Axios 的 **error 分支**，而不是 success 分支里的 code 判断。
 *
 * ⚠️ 实测：HTTP 401 的响应体里业务码是 **4011**（`{"code":4011,"msg":"登录信息无效，请重新登录"}`），
 *    不是 401。所以**绝不能**写成 `body.code === 401` 去判断登录失效 ——
 *    那样续期逻辑永远不会触发，而这是静默的（页面只是"偶尔莫名跳登录"）。
 */
/** HTTP 401：未登录 / token 失效 → 续期或跳登录 */
export const CODE_UNAUTHORIZED = 401
/** HTTP 403：已登录但角色不够 → ★ 只提示，绝不能跳登录 */
export const CODE_FORBIDDEN = 403
