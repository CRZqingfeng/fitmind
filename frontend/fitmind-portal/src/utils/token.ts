/**
 * token 的"单一事实来源"
 *
 * ★ 为什么不放在 Pinia store 里：
 *   request.ts（Axios 拦截器）需要读写 token，而 store 里的 action 又要用 request.ts 发请求。
 *   如果 token 放在 store，就会形成 request → store → request 的循环依赖。
 *   把 token 抽成一个不依赖任何东西的小模块，两边都从它拿，问题消失。
 *
 * ★ 为什么内存 + localStorage 两份：
 *   localStorage 是为了刷新页面不丢登录态；内存副本是为了让拦截器读写不每次都碰磁盘。
 *
 * 后端概念映射：这里相当于"把 token 从 Bean 里挪到一个无依赖的静态工具类"，
 * 类似 Java 里为了避免循环依赖把公共状态抽到独立的 Holder。
 */
const TOKEN_KEY = 'fitmind:token'

/** 内存副本：拦截器每次请求都要读，避免频繁访问 localStorage */
let memoryToken: string | null = readFromStorage()

function readFromStorage(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    // 隐私模式 / 存储被禁用时 localStorage 会抛错，降级为"仅内存"
    return null
  }
}

export function getToken(): string | null {
  return memoryToken
}

export function setToken(token: string | null): void {
  memoryToken = token
  try {
    if (token) {
      localStorage.setItem(TOKEN_KEY, token)
    } else {
      localStorage.removeItem(TOKEN_KEY)
    }
  } catch {
    // 存不进去不影响本次会话使用（内存里还有）
  }
}

export function clearToken(): void {
  setToken(null)
}
