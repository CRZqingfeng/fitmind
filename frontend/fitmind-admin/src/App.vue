<script setup lang="ts">
/**
 * 根组件
 *
 * 只做两件事：挂 Element Plus 的中文 locale + 渲染 RouterView。
 *
 * 布局不在这里判断 —— 登录页是独立整页，其余页面共用 DefaultLayout，
 * 这个区分交给路由表表达（见 router/index.ts 的嵌套路由）。
 *
 * ★ 为什么 locale 从 main.ts 搬到这里（M7-9 按需引入）：
 *   以前是 `app.use(ElementPlus, { locale: zhCn })`，那依赖 ElementPlus 的
 *   **全量注册**。改成按需引入后这个插件没了，官方推荐的替代就是
 *   `<el-config-provider :locale="zhCn">`。
 *   （`el-config-provider` 本身也由按需插件自动注册，不需要手动 import。）
 */
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<template>
  <el-config-provider :locale="zhCn">
    <RouterView />
  </el-config-provider>
</template>
