import axios from 'axios'
import type { AxiosError, AxiosRequestConfig, AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

import { CODE_FORBIDDEN, CODE_UNAUTHORIZED, SUCCESS_CODE } from '@/types/common'
import type { R } from '@/types/common'
import { clearToken, getToken, setToken } from '@/utils/token'

/**
 * Axios 封装（含 401 无感续期）
 *
 * 后端概念映射（给熟悉 Spring 的读者）：
 *   请求拦截器 ≈ HandlerInterceptor.preHandle   —— 统一加鉴权头
 *   响应拦截器 ≈ HandlerInterceptor.postHandle  —— 统一解包 / 统一处理异常
 *   本文件的定位相当于前端版的 CommonExceptionAdvice + 鉴权拦截器
 *
 * 与后端对齐的三个关键事实（都验证过后端源码，不是猜的）：
 *   1. Authorization 头是【裸 token】，不带 "Bearer " 前缀
 *      —— 后端 AuthUtil.parseToken 会剥 "Bearer "，但本项目既有验收脚本一律用裸 token
 *   2. 刷新 token 走【HttpOnly Cookie】（网关 excludePath 的 GET /as/accounts/refresh
 *      用 @CookieValue 读），所以必须 withCredentials: true
 *   3. 401 / 403 是【HTTP 状态码】（后端 @ResponseStatus 标注），
 *      不是 HTTP 200 里的业务码 —— 所以要在 error 分支处理，不是 success 分支
 */

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 业务错误时是否不弹全局提示（由调用方自己处理，如登录页内联报错） */
    silent?: boolean
    /** 内部标记：该请求已因 401 续期后重放过一次，避免死循环 */
    _retried?: boolean
  }
}

/** 统一的请求错误对象，调用方可用 `instanceof ApiError` 判断 */
export class ApiError extends Error {
  code: number

  constructor(code: number, msg: string) {
    super(msg)
    this.name = 'ApiError'
    this.code = code
  }
}

const baseURL = import.meta.env.VITE_API_BASE

/**
 * 裸实例：没有挂任何拦截器，专供 token 续期使用
 * ★ 必须裸 —— 续期请求若走主实例，它自己 401 时会再次触发续期，直接死循环
 */
const rawHttp = axios.create({
  baseURL,
  timeout: 15000,
  withCredentials: true,
})

/**
 * 主实例
 *
 * ★★ withCredentials 是这里最容易踩的坑：
 *    刷新 token 依赖 HttpOnly Cookie，而【跨源】请求默认既不带 Cookie、
 *    也不保存响应里的 Set-Cookie。开发时前端在 :5173、网关在 :10010，属于跨源。
 *    不开这个开关的表现是：登录当下一切正常，**刷新页面就掉登录态**，
 *    而且没有任何报错 —— 静默失败最难查。
 */
const service = axios.create({
  baseURL,
  timeout: 30000,
  withCredentials: true,
})

// ==================== 请求拦截器 ====================

service.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getToken()
  if (token) {
    // ★ 裸 token（见文件头第 1 条）
    config.headers.Authorization = token
  }
  return config
})

// ==================== 401 续期 ====================

type RetriableConfig = InternalAxiosRequestConfig & { _retried?: boolean }

/**
 * 正在进行的续期请求
 * ★ 用"共享 Promise"把并发 401 收敛成一次续期：
 *   页面同时发 5 个请求，token 失效时 5 个都会 401。
 *   若每个都去续期 → 打出 5 次 refresh，且后 4 次拿到的还是同一个新 token（浪费 + 竞态）。
 */
let refreshing: Promise<string | null> | null = null

/**
 * 尝试续期一次，返回新 token（失败返回 null）。
 *
 * ★ 导出它是因为 **SSE 流不能走 Axios**（要用 fetch + ReadableStream 才能拿到
 *   增量分片），于是它享受不到 Axios 拦截器里的那套 401 续期。
 *   与其在 SSE 客户端里再写一份续期逻辑（两份必然走偏），不如共用这一个入口 ——
 *   连"并发 401 只续一次"的共享 Promise 也一并复用了。
 */
export function refreshTokenOnce(): Promise<string | null> {
  if (!refreshing) {
    refreshing = rawHttp
      .get<R<string>>('/as/accounts/refresh')
      .then((res) => {
        const body = res.data
        if (body && body.code === SUCCESS_CODE && typeof body.data === 'string') {
          setToken(body.data)
          return body.data
        }
        return null
      })
      .catch(() => null)
      .finally(() => {
        // 无论成败都清空，让下一次 401 能重新尝试
        refreshing = null
      })
  }
  return refreshing
}

/**
 * 登录态彻底失效时的兜底：清 token + 跳登录页
 *
 * 这里用 window.location.href 整页跳转而不是 router.push：
 *   ① 避免 request.ts → router → store → api → request.ts 的循环依赖
 *   ② 整页刷新能顺带清掉内存里所有残留状态（比局部跳转更干净）
 */
export function forceLogin(): void {
  clearToken()
  const { pathname, search, hash } = window.location
  // 已经在登录页就不要再跳，否则会把当前 redirect 参数覆盖掉
  if (pathname.startsWith('/login')) return
  const redirect = encodeURIComponent(pathname + search + hash)
  window.location.href = `/login?redirect=${redirect}`
}

/**
 * 这些接口的 401 语义是"凭证不对"，不是"登录态过期"，绝不能触发续期
 *
 * ★ 这是一个实际会踩的坑：密码输错时后端返回 401，
 *   若无差别续期，用户点"登录"会先打一次 refresh（必然失败）再跳登录页，
 *   表现为"按钮卡住一下然后清空表单"，排查时很难联想到续期逻辑。
 */
const AUTH_ENDPOINTS = ['/as/accounts/login', '/as/accounts/admin/login', '/as/accounts/refresh']

function isAuthEndpoint(url: string): boolean {
  return AUTH_ENDPOINTS.some((p) => url.includes(p))
}

async function handleUnauthorized(error: AxiosError<R>): Promise<never> {
  const config = error.config as RetriableConfig | undefined
  if (!config) {
    return Promise.reject(new ApiError(CODE_UNAUTHORIZED, '登录已过期，请重新登录'))
  }

  // 已经重放过一次仍然 401 → 说明续期没解决问题，再放行就是死循环
  if (config._retried) {
    forceLogin()
    return Promise.reject(new ApiError(CODE_UNAUTHORIZED, '登录已过期，请重新登录'))
  }

  const newToken = await refreshTokenOnce()
  if (!newToken) {
    forceLogin()
    return Promise.reject(new ApiError(CODE_UNAUTHORIZED, '登录已过期，请重新登录'))
  }

  config._retried = true
  // 重放原请求。请求拦截器会重新执行，用刚写入的新 token 覆盖 Authorization 头
  return service.request(config) as never
}

// ==================== 响应拦截器 ====================

service.interceptors.response.use(
  /**
   * 返回类型标 any 是必须的：axios 1.x 的类型要求拦截器返回 AxiosResponse，
   * 而这里刻意要把它**解包成业务 data**（这正是这层封装存在的意义）。
   * 类型落差不在这里硬凑，统一由下方 request() 的断言兜住 ——
   * 否则要写一层比收益大得多的类型体操。
   */
  (response: AxiosResponse): any => {
    const body = response.data as R | undefined

    // 非 R 结构的响应（文件流、纯文本等）原样返回，不强行解包
    if (!body || typeof body !== 'object' || !('code' in body)) {
      return body
    }

    // 成功：★ 直接返回 data，业务代码拿到的就是数据本身
    // （Promise<UserDetail> 而不是 Promise<AxiosResponse<R<UserDetail>>>）
    if (body.code === SUCCESS_CODE) {
      return body.data
    }

    // HTTP 200 但业务码非 200（后端业务校验失败走这条路）
    const err = new ApiError(body.code, body.msg || '请求失败')
    if (!response.config.silent) {
      if (body.code === CODE_FORBIDDEN) {
        ElMessage.warning(err.message)
      } else {
        ElMessage.error(err.message)
      }
    }
    return Promise.reject(err)
  },
  async (error: AxiosError<R>) => {
    const response = error.response
    const url = error.config?.url ?? ''

    // 压根没有响应：后端没启动 / 跨域被浏览器拦下 / 超时
    if (!response) {
      const msg = '无法连接服务器，请确认后端服务已启动'
      if (!error.config?.silent) ElMessage.error(msg)
      return Promise.reject(new ApiError(-1, msg))
    }

    const status = response.status
    const msg = response.data?.msg || ''

    if (status === CODE_UNAUTHORIZED) {
      if (isAuthEndpoint(url)) {
        // 登录/续期接口自己的 401：是"账号密码错"，交给调用方展示
        return Promise.reject(new ApiError(CODE_UNAUTHORIZED, msg || '登录信息有误'))
      }
      return handleUnauthorized(error)
    }

    if (status === CODE_FORBIDDEN) {
      // ★ 403 = 已登录但角色不够。绝不能跳登录页 ——
      //   用户明明登录着却被要求重新登录，只会更困惑（这是 M6-7 刚收敛的语义）
      const forbiddenMsg = msg || '当前账号无权访问该功能'
      if (!error.config?.silent) ElMessage.warning(forbiddenMsg)
      return Promise.reject(new ApiError(CODE_FORBIDDEN, forbiddenMsg))
    }

    const fallback = msg || `请求失败（HTTP ${status}）`
    if (!error.config?.silent) ElMessage.error(fallback)
    return Promise.reject(new ApiError(status, fallback))
  },
)

// ==================== 对外方法 ====================

/**
 * 发起请求并直接拿到 `data`
 *
 * 类型断言说明：响应拦截器已经把 AxiosResponse 解包成 data，
 * 但 axios 的类型定义不知道这件事，所以这里要手动断言一次。
 * 这是 axios 封装的固有取舍（换来的是业务代码不用到处写 .data）。
 */
function request<T>(config: AxiosRequestConfig): Promise<T> {
  return service.request(config) as unknown as Promise<T>
}

export const http = {
  get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return request<T>({ ...config, method: 'GET', url })
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return request<T>({ ...config, method: 'POST', url, data })
  },
  put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return request<T>({ ...config, method: 'PUT', url, data })
  },
  delete<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return request<T>({ ...config, method: 'DELETE', url })
  },
}

export default http
