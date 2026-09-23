<script setup lang="ts">
import { computed, onMounted } from 'vue'

import {
  Bell,
  Calendar,
  ChatDotRound,
  List,
  Medal,
  Reading,
  RefreshLeft,
  ShoppingCart,
} from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

/**
 * 我的（个人中心）
 *
 * M7-1 只做"证明登录态与用户信息链路是通的"这件事：
 * 页面上的每个字段都直接来自 `GET /us/users/me`，没有任何前端硬编码，
 * 所以它天然就是验收脚本的交叉验证对象（页面值必须 == 接口值）。
 */
const auth = useAuthStore()
const router = useRouter()

/** 快捷入口：学习（M7-4）与交易（M7-3）都收在这里 */
const quickLinks = [
  { path: '/my-courses', label: '我的课程', desc: '继续训练', icon: Reading, testid: 'me-link-courses' },
  { path: '/plans', label: '训练计划', desc: '每周频率', icon: Calendar, testid: 'me-link-plans' },
  { path: '/community', label: '社区', desc: '问答与笔记', icon: ChatDotRound, testid: 'me-link-community' },
  { path: '/messages', label: '消息中心', desc: '通知与提醒', icon: Bell, testid: 'me-link-messages' },
  { path: '/growth', label: '成长中心', desc: '签到与积分', icon: Medal, testid: 'me-link-growth' },
  { path: '/cart', label: '购物车', desc: '待结算的课程', icon: ShoppingCart, testid: 'me-link-cart' },
  { path: '/orders', label: '我的订单', desc: '待支付 / 已报名', icon: List, testid: 'me-link-orders' },
  { path: '/refunds', label: '我的退款', desc: '查看退款进度', icon: RefreshLeft, testid: 'me-link-refunds' },
]

onMounted(async () => {
  if (auth.isLogin && !auth.user) {
    try {
      await auth.fetchMe()
    } catch {
      /* 已由 request.ts 统一处理 */
    }
  }
})

const fields = computed(() => {
  const u = auth.user
  if (!u) return []
  return [
    { label: '用户 ID', value: u.id },
    { label: '手机号', value: u.cellPhone },
    { label: '姓名 / 昵称', value: u.name },
    { label: '用户名', value: u.username },
    { label: '角色', value: auth.roleLabel },
    { label: '性别', value: genderText(u.gender) },
    { label: '邮箱', value: u.email },
    { label: '生日', value: u.birthday },
    { label: '所在地区', value: regionText(u.province, u.city, u.district) },
    { label: '账号状态', value: u.status === 1 ? '正常' : u.status === 0 ? '已禁用' : null },
    { label: '注册时间', value: u.createTime },
    { label: '健身宣言', value: u.intro },
  ]
})

function genderText(gender: number | null): string | null {
  if (gender === 0) return '男'
  if (gender === 1) return '女'
  return null
}

function regionText(...parts: (string | null)[]): string | null {
  const joined = parts.filter((p) => p).join(' ')
  return joined || null
}

/** 后端未填字段一律返回 null，界面上统一显示为 — 而不是空白（空白会被误认为页面坏了） */
function display(value: unknown): string {
  return value === null || value === undefined || value === '' ? '—' : String(value)
}
</script>

<template>
  <div class="fm-page">
    <el-card shadow="never">
      <template #header>
        <div class="fm-card-title">个人资料</div>
      </template>

      <el-skeleton v-if="auth.isLogin && !auth.user" :rows="6" animated />

      <el-descriptions v-else data-testid="me-profile" :column="2" border>
        <el-descriptions-item v-for="field in fields" :key="field.label" :label="field.label">
          {{ display(field.value) }}
        </el-descriptions-item>
      </el-descriptions>

      <el-empty v-if="!auth.isLogin" description="未登录" />
    </el-card>

    <el-card shadow="never" class="trade-card">
      <template #header>
        <div class="fm-card-title">快捷入口</div>
      </template>
      <div class="quick-links">
        <div
          v-for="link in quickLinks"
          :key="link.path"
          class="quick-link"
          :data-testid="link.testid"
          @click="router.push(link.path)"
        >
          <el-icon class="quick-icon"><component :is="link.icon" /></el-icon>
          <span class="quick-label">{{ link.label }}</span>
          <span class="quick-desc">{{ link.desc }}</span>
        </div>
      </div>
    </el-card>

    <el-alert class="source-tip" type="info" :closable="false">
      <template #title>
        本页所有字段直接来自 <code>GET /us/users/me</code>，前端无任何硬编码 ——
        因此它也是验收脚本的交叉验证对象（页面显示值必须等于接口返回值）。
      </template>
    </el-alert>
  </div>
</template>

<style scoped>
.trade-card {
  margin-top: 16px;
}

.quick-links {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.quick-link {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.quick-link:hover {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.quick-icon {
  font-size: 20px;
  color: var(--el-color-primary);
}

.quick-label {
  font-size: 15px;
  font-weight: 600;
}

.quick-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-left: auto;
}

.source-tip {
  margin-top: 16px;
}

code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--el-fill-color);
  font-family: ui-monospace, Consolas, monospace;
}
</style>
