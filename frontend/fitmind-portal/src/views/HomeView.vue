<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { ArrowRight } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'

import { getMyInterests, recommendBest, recommendFree, recommendNewest } from '@/api/search'
import CourseCard from '@/components/CourseCard.vue'
import { useAuthStore } from '@/stores/auth'
import type { CourseSearchVO } from '@/types/course'

/**
 * 首页（猜你喜欢 / 免费专区 / 最新上架）
 *
 * 三个推荐接口互相独立，用 `Promise.all` 并行取 —— 串行会让首屏多等两轮。
 * 任一失败不影响其它区块（各自 catch 成空数组），页面不会整片空白。
 */
const router = useRouter()
const auth = useAuthStore()

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const loading = ref(true)
const best = ref<CourseSearchVO[]>([])
const free = ref<CourseSearchVO[]>([])
const newest = ref<CourseSearchVO[]>([])
/** 我的训练目标（一级分类 id） */
const myInterests = ref<string[]>([])

const sections = computed(() => [
  { key: 'best', title: '猜你喜欢', desc: '按评分与报名热度挑的', list: best.value, testid: 'home-best' },
  { key: 'free', title: '免费专区', desc: '不花钱先练起来', list: free.value, testid: 'home-free' },
  { key: 'newest', title: '最新上架', desc: '刚上架的训练计划', list: newest.value, testid: 'home-newest' },
])

const hasInterests = computed(() => myInterests.value.length > 0)

onMounted(async () => {
  loading.value = true
  const [b, f, n, i] = await Promise.all([
    recommendBest(8).catch(() => []),
    recommendFree(8).catch(() => []),
    recommendNewest(8).catch(() => []),
    getMyInterests().catch(() => []),
  ])
  best.value = b
  free.value = f
  newest.value = n
  myInterests.value = i || []
  loading.value = false
})
</script>

<template>
  <div class="fm-page" v-loading="loading">
    <el-card shadow="never" class="welcome-card">
      <div class="welcome" data-testid="home-welcome">
        <div class="welcome-text">
          <h2>{{ greeting }}，{{ auth.displayName || '欢迎回来' }}</h2>
          <p>
            今天练什么？从下面的推荐挑一门，或者去
            <el-link type="primary" :underline="false" @click="router.push({ name: 'courses' })">
              课程库
            </el-link>
            按训练目标筛选。
          </p>
        </div>
        <div class="welcome-right">
          <el-tag v-if="auth.roleLabel" type="warning" size="large" effect="dark">
            {{ auth.roleLabel }}
          </el-tag>
          <el-button type="primary" :icon="ArrowRight" @click="router.push({ name: 'courses' })">
            浏览全部课程
          </el-button>
        </div>
      </div>
    </el-card>

    <div v-for="sec in sections" :key="sec.key" class="section">
      <div class="section-head">
        <h3>{{ sec.title }}</h3>
        <span class="section-desc">{{ sec.desc }}</span>
      </div>

      <div v-if="sec.list.length" class="course-grid" :data-testid="sec.testid">
        <CourseCard v-for="c in sec.list" :key="c.id" :course="c" />
      </div>
      <el-empty v-else-if="!loading" :description="`「${sec.title}」暂时没有课程`" :image-size="70" />
    </div>

    <el-alert
      v-if="!loading && !hasInterests"
      class="tip"
      type="info"
      :closable="false"
      show-icon
      title="还没有设置训练目标"
    >
      <div class="tip-body">
        设置训练目标后，首页推荐与 AI 教练的回答都会更贴合你的情况。
        （设置入口在 M7-6 的体测/档案页，当前训练目标来自 <code>/ss/interests</code>）
      </div>
    </el-alert>
  </div>
</template>

<style scoped>
.welcome-card {
  margin-bottom: 20px;
}

.welcome {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.welcome-text h2 {
  margin: 0 0 8px;
  font-size: 20px;
}

.welcome-text p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
  max-width: 660px;
}

.welcome-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.section {
  margin-bottom: 26px;
}

.section-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 12px;
}

.section-head h3 {
  margin: 0;
  font-size: 17px;
  position: relative;
  padding-left: 10px;
}

.section-head h3::before {
  content: '';
  position: absolute;
  left: 0;
  top: 4px;
  bottom: 4px;
  width: 4px;
  border-radius: 2px;
  background: var(--el-color-primary);
}

.section-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.course-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(230px, 1fr));
  gap: 16px;
}

.tip {
  margin-bottom: 20px;
}

.tip-body {
  font-size: 12px;
  line-height: 1.7;
}

.tip-body code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--el-fill-color);
  font-family: ui-monospace, Consolas, monospace;
}
</style>
