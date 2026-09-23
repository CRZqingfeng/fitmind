/// <reference types="vite/client" />

/**
 * 环境变量类型声明
 * 用途：让 `import.meta.env.VITE_API_BASE` 有确定类型，而不是 any
 * （值定义在 .env.development / .env.production）
 */
interface ImportMetaEnv {
  /** 后端网关地址。开发环境直连 http://localhost:10010，生产走同源 /api */
  readonly VITE_API_BASE: string
  /** 应用显示名 */
  readonly VITE_APP_NAME: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
