<script setup lang="ts">
import { computed, onMounted } from 'vue'

import {
  ArrowDown,
  Bell,
  Calendar,
  ChatDotRound,
  HomeFilled,
  MagicStick,
  Medal,
  Notebook,
  Odometer,
  Reading,
  ShoppingCart,
  User,
  VideoCamera,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { useCartStore } from '@/stores/cart'
import { useMessageStore } from '@/stores/message'

/**
 * 主布局：顶部导航 + 内容区
 *
 * 菜单只放当前里程碑已实现的页面 —— 不预先摆一堆点了 404 的入口，
 * 那会让"没做完"看起来像"做坏了"。后续里程碑逐个补。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()
const messageStore = useMessageStore()

/**
 * 导航菜单
 *
 * ★ M7-4 起把「购物车」「我的订单」从这里挪到了顶栏图标与用户下拉：
 *   菜单是**页面浏览路径**，而那两个是"随时可能点开的入口"。
 *   每加一个里程碑就往菜单里塞一项，很快会挤成一条谁都不看的横条。
 */
const menus = [
  { path: '/', label: '首页', icon: HomeFilled },
  { path: '/courses', label: '课程', icon: Notebook },
  { path: '/my-courses', label: '我的课程', icon: Reading },
  // M11-2 计划模块：★ 只加这一项。参考 App 底部有"训练/饮食/我的计划"多个 tab，
  // 但菜单是**页面浏览路径**，不是功能清单 —— 内部用 el-tabs 分流就够，
  // 拆成 4 项会把这条横条挤成谁也不看的标签墙（M7-4 已经从菜单里挪走过购物车）。
  { path: '/plan', label: '计划', icon: Calendar },
  { path: '/community', label: '社区', icon: ChatDotRound },
  { path: '/growth', label: '成长', icon: Medal },
  { path: '/assessment', label: '体测', icon: Odometer },
  { path: '/action-assess', label: '动作考核', icon: VideoCamera },
  { path: '/ai', label: 'AI 教练', icon: MagicStick },
  { path: '/me', label: '我的', icon: User },
]

const activeMenu = computed(() => route.path)

onMounted(async () => {
  // 刷新页面后 store 是空的（只有 token），需要补拉一次用户信息。
  // 失败不处理：token 失效的话拦截器会去续期或跳登录，这里静默即可。
  if (auth.isLogin && !auth.user) {
    try {
      await auth.fetchMe()
    } catch {
      /* 已由 request.ts 统一处理 */
    }
  }
  // 购物车角标同理：刷新页面后 store 是空的，不补这一次就会出现
  // "购物车里明明有东西，顶栏角标却是空的"
  if (auth.isLogin && !cart.loaded) {
    await cart.load()
  }
  // 消息未读数也是同一套（静默拉取，失败不影响当前页面）
  if (auth.isLogin && !messageStore.loaded) {
    await messageStore.load()
  }
})

/** 下拉菜单的跳转目标（表驱动，省得堆一串 if-else） */
const DROPDOWN_ROUTES: Record<string, string> = {
  me: '/me',
  orders: '/orders',
  plans: '/plans',
  refunds: '/refunds',
}

async function handleCommand(command: string) {
  const target = DROPDOWN_ROUTES[command]
  if (target) {
    router.push(target)
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
    // 用户点了取消，ElMessageBox 会 reject，这里必须吞掉
    return
  }

  await auth.logout()
  // ★ 必须清掉购物车的本地镜像：Cookie 是按 host 存的、localStorage 也只清了 token，
  //   不 reset 的话换个账号登录会直接看到上一个人的购物车
  cart.reset()
  messageStore.reset()
  ElMessage.success('已退出登录')
  router.push({ name: 'login' })
}
</script>

<template>
  <el-container class="app-layout">
    <el-header class="app-header">
      <div class="brand" @click="router.push('/')">
        <span class="brand-mark">FitMind</span>
        <span class="brand-sub">AI 健身教练</span>
      </div>

      <el-menu class="app-nav" mode="horizontal" :default-active="activeMenu" router :ellipsis="false">
        <el-menu-item v-for="menu in menus" :key="menu.path" :index="menu.path">
          <el-icon><component :is="menu.icon" /></el-icon>
          <span>{{ menu.label }}</span>
        </el-menu-item>
      </el-menu>

      <div class="header-right">
        <!-- 消息：未读角标是"提醒"，用户需要一眼看到有没有人回复他 -->
        <el-badge
          :value="messageStore.unread"
          :hidden="messageStore.unread === 0"
          :max="99"
          class="cart-entry"
          data-testid="header-message"
        >
          <el-button text :icon="Bell" @click="router.push('/messages')" />
        </el-badge>

        <!-- 购物车：从菜单移到顶栏图标。★ 角标保留 ——
             "加购成功了没"必须立刻看得见，否则用户会以为没加进去、反复点
             （后端虽然幂等，但反复点击的体验很差）。 -->
        <el-badge
          :value="cart.count"
          :hidden="cart.count === 0"
          :max="99"
          class="cart-entry"
          data-testid="header-cart"
        >
          <el-button text :icon="ShoppingCart" @click="router.push('/cart')" />
        </el-badge>

        <el-tag v-if="auth.roleLabel" type="warning" effect="plain" size="small">
          {{ auth.roleLabel }}
        </el-tag>
        <el-dropdown trigger="click" @command="handleCommand">
          <span class="user-trigger">
            <el-avatar :size="28" :src="auth.user?.icon ?? undefined">
              {{ auth.displayName.slice(0, 1) || '?' }}
            </el-avatar>
            <span class="user-name">{{ auth.displayName || '未登录' }}</span>
            <el-icon class="user-arrow"><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="me">个人中心</el-dropdown-item>
              <el-dropdown-item command="orders">我的订单</el-dropdown-item>
              <!--
                ⚠️ 这一项指向 `/plans`（**课程学习计划**：某门课每周练几次），
                与菜单里的「计划」(`/plan`，训练编排) 是两件事。
                文案改成「课程计划」是为了不让两个同义入口打架 —— 只改文案，不动跳转，
                `DROPDOWN_ROUTES.plans` 仍然指向 /plans，PlansView.vue 一行没动。
              -->
              <el-dropdown-item command="plans">课程计划</el-dropdown-item>
              <el-dropdown-item command="refunds">我的退款</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </el-header>

    <el-main class="app-main">
      <RouterView />
    </el-main>
  </el-container>
</template>

<style scoped>
.app-layout {
  min-height: 100vh;
}

.app-header {
  display: flex;
  align-items: center;
  gap: 24px;
  height: var(--fm-header-height);
  padding: 0 24px;
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-light);
  position: sticky;
  top: 0;
  z-index: 100;
}

.brand {
  display: flex;
  align-items: baseline;
  gap: 8px;
  cursor: pointer;
  flex-shrink: 0;
}

.brand-mark {
  font-size: 20px;
  font-weight: 700;
  color: var(--el-color-primary);
  letter-spacing: 0.5px;
}

.brand-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.app-nav {
  flex: 1;
  border-bottom: none;
  min-width: 0;
}

/*
 * ★ 横向菜单的第 10 项（M11-2「计划」）会把顶栏顶出横向滚动条（实测 1440 宽下溢出 58px）。
 *   el-menu 这里配了 :ellipsis="false"（不做"..."折叠），所以只能靠压缩单项内边距吸收。
 *   不用媒体查询直接改全局 padding，是因为宽屏（≥1500）本来放得下，没必要一起挤。
 */
@media (max-width: 1500px) {
  .app-nav :deep(.el-menu-item) {
    padding: 0 10px;
  }
}

/* 购物车图标 + 角标（顶栏右侧） */
.cart-entry {
  display: inline-flex;
  line-height: 1;
  margin-right: 4px;
}

.cart-entry :deep(.el-badge__content) {
  top: 6px;
  right: 12px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
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

.app-main {
  padding: 20px 24px 40px;
}
</style>
