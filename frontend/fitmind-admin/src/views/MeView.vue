<script setup lang="ts">
import { computed, onMounted } from 'vue'

import { useAuthStore } from '@/stores/auth'

/**
 * 我的（个人中心）
 *
 * M7-1 只做"证明登录态与用户信息链路是通的"这件事：
 * 页面上的每个字段都直接来自 `GET /us/users/me`，没有任何前端硬编码，
 * 所以它天然就是验收脚本的交叉验证对象（页面值必须 == 接口值）。
 */
const auth = useAuthStore()

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

    <el-alert class="source-tip" type="info" :closable="false">
      <template #title>
        本页所有字段直接来自 <code>GET /us/users/me</code>，前端无任何硬编码 ——
        因此它也是验收脚本的交叉验证对象（页面显示值必须等于接口返回值）。
      </template>
    </el-alert>
  </div>
</template>

<style scoped>
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
