<script setup lang="ts">
import { computed, onMounted } from 'vue'

import {
  ArrowDown,
  DataLine,
  MagicStick,
  Notebook,
  PriceTag,
  RefreshLeft,
  Trophy,
  User,
  VideoCamera,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

/**
 * 管理端布局：左侧菜单 + 顶部栏 + 内容区（后台的经典结构）
 *
 * 与用户端默认布局的区别：用户端是"顶部横向导航"，管理端用竖向菜单 ——
 * 因为后台的功能项会随 M7-8 增长到 5 个以上，横向导航放不下。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/**
 * 菜单图标按路由名映射（避免在 router 的 meta 里塞组件引用，那会让路由表变成 UI 配置）
 *
 * ★ 加了页面只改 router 就会出现在菜单里；**忘了加图标这里不会报错**，
 *   只会静默退化成默认的 User 图标 —— 所以顺序是：加路由 → 顺手加图标。
 */
const menuIcons: Record<string, typeof User> = {
  dashboard: DataLine,
  courses: Notebook,
  media: VideoCamera,
  refunds: RefreshLeft,
  coupons: PriceTag,
  'ai-ops': MagicStick,
  'action-assess': Trophy,
}

/**
 * 菜单直接从路由表推导
 * ★ 好处：M7-8 补 5 个页面时只改 router/index.ts，不会出现"加了路由忘了加菜单"
 */
const menus = computed(() => {
  const root = router.options.routes.find((r) => r.path === '/')
  return (root?.children ?? [])
    .filter((child) => !child.meta?.hideInMenu)
    .map((child) => ({
      path: child.path ? `/${child.path}` : '/',
      title: child.meta?.title ?? String(child.name ?? ''),
      icon: menuIcons[String(child.name ?? '')] ?? User,
    }))
})

const activeMenu = computed(() => route.path)

onMounted(async () => {
  if (auth.isLogin && !auth.user) {
    try {
      await auth.fetchMe()
    } catch {
      /* 已由 request.ts 统一处理 */
    }
  }
})

async function handleCommand(command: string) {
  if (command === 'me') {
    router.push('/me')
    return
  }
  if (command !== 'logout') return

  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      type: 'warning',
      confirmButtonText: '退出',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }

  await auth.logout()
  ElMessage.success('已退出登录')
  router.push({ name: 'login' })
}
</script>

<template>
  <el-container class="admin-layout">
    <el-aside width="210px" class="admin-aside">
      <div class="aside-brand">
        <span class="brand-mark">FitMind</span>
        <span class="brand-sub">管理后台</span>
      </div>

      <el-menu class="aside-menu" :default-active="activeMenu" router>
        <el-menu-item v-for="menu in menus" :key="menu.path" :index="menu.path">
          <el-icon><component :is="menu.icon" /></el-icon>
          <span>{{ menu.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="admin-header">
        <div class="page-title">{{ route.meta.title }}</div>

        <div class="header-right">
          <el-tag v-if="auth.roleLabel" type="warning" effect="dark" size="small">
            {{ auth.roleLabel }}
          </el-tag>
          <el-dropdown trigger="click" @command="handleCommand">
            <span class="user-trigger">
              <el-avatar :size="28" :src="auth.user?.icon ?? undefined">
                {{ auth.displayName.slice(0, 1) || '?' }}
              </el-avatar>
              <span class="user-name" data-testid="admin-user-name">{{ auth.displayName || '未登录' }}</span>
              <el-icon class="user-arrow"><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="me">账号信息</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="admin-main">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-layout {
  min-height: 100vh;
}

.admin-aside {
  background: #1f2733;
  display: flex;
  flex-direction: column;
}

.aside-brand {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 0 18px;
  height: var(--fm-header-height);
  line-height: var(--fm-header-height);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.brand-mark {
  font-size: 19px;
  font-weight: 700;
  color: var(--el-color-primary);
  letter-spacing: 0.5px;
}

.brand-sub {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.5);
}

.aside-menu {
  flex: 1;
  border-right: none;
  background: transparent;
  --el-menu-text-color: rgba(255, 255, 255, 0.75);
  --el-menu-hover-text-color: #fff;
  --el-menu-active-color: var(--el-color-primary);
  --el-menu-hover-bg-color: rgba(255, 255, 255, 0.06);
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--fm-header-height);
  padding: 0 24px;
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-light);
}

.page-title {
  font-size: 16px;
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  outline: none;
  color: var(--el-text-color-primary);
}

.user-name {
  font-size: 14px;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-arrow {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.admin-main {
  padding: 20px 24px 40px;
  background: #f5f7fa;
}
</style>
