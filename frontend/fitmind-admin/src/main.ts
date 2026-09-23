import { createPinia } from 'pinia'

import { createApp } from 'vue'

import App from './App.vue'
import router from './router'

// ★★ Element Plus **按需引入**（M7-9 产物优化）。
//   原来这里写的是：
//     import ElementPlus from 'element-plus'
//     import 'element-plus/dist/index.css'
//     ...
//     app.use(ElementPlus, { locale: zhCn })
//   → 把**整个组件库**（连用不到的组件与样式）都打了进去（主 chunk 872KB / gzip 278KB）。
//   现在组件与样式改由 unplugin-vue-components 在**编译期**按需注入（见 vite.config.ts）。
//
//   ⚠️ 但有两件事必须在这里**手动补上**，否则是"不报错、但样式悄悄坏掉"：
//     ① **函数式组件**（ElMessage / ElMessageBox）在代码里是**显式 import** 的，
//        不走模板解析 → 按需插件不会给它们注入样式。
//        漏了的症状：提示/确认框**能弹出来、但完全没有样式**（裸文字贴在页面上）。
//     ② `--el-*` 基础变量：自定义样式（assets/styles/index.css）里也会用到，
//        所以显式引一下 base。
//   ★ locale（zh-cn）也换了位置：见 App.vue 的 <el-config-provider>。
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/theme-chalk/base.css'
import '@/assets/styles/index.css'

const app = createApp(App)

/**
 * ⚠️ 注意这里没有全局注册 Element Plus 图标。
 * 图标包有上千个组件，全量注册会显著撑大产物；
 * 各组件里"用到哪个 import 哪个"即可（`import { HomeFilled } from '@element-plus/icons-vue'`）。
 */

// Pinia 要在 router 之前装：守卫或组件可能在路由解析阶段就用 store
app.use(createPinia())
app.use(router)

app.mount('#app')
