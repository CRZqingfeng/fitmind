import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    /**
     * ★★ Element Plus **按需引入**（M7-9 产物优化）。
     *
     * 改造前：`main.ts` 里 `app.use(ElementPlus)` + `import 'element-plus/dist/index.css'`
     *   → 把**整个组件库**（连用不到的组件与样式）都打进主 chunk（872KB / gzip 278KB）。
     * 改造后：模板里写 `<el-button>` 时，这个插件在**编译期**把它解析成
     *   `import { ElButton } from 'element-plus'` 并按需引入对应样式。
     *
     * ⚠️ 两个必须知道的坑：
     *   1. **函数式组件**（`ElMessage` / `ElMessageBox`）是**显式 import** 的，
     *      不走模板解析 → 这个插件**不会**给它们注入样式。
     *      漏了的症状是"提示能弹出来、但完全没有样式"（裸文字贴在页面上），
     *      **不报错**、也不影响验收里的 DOM 断言 —— 所以极易漏。
     *      → 已在 `main.ts` 里显式引入这两个组件的样式。
     *   2. `locale`（zh-cn）以前靠 `app.use(ElementPlus, { locale })` 注册，
     *      全量注册没了之后要改用组件 `<el-config-provider :locale="zhCn">`（见 `App.vue`）。
     */
    Components({
      resolvers: [ElementPlusResolver()],
      // 生成的类型声明，让 TS 认识模板里的全局组件（固定路径，别用默认的根目录）
      dts: 'src/components.d.ts',
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 与 fitmind-portal (5173) 区分开
    port: 5174,
    strictPort: true,
  },
})
