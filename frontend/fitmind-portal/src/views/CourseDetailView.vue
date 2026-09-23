<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { ArrowLeft, ShoppingCart } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { getCourseBaseInfo, getCourseCatalogues, getCourseTeachers } from '@/api/course'
import { enrollLesson } from '@/api/learning'
import CourseCover from '@/components/CourseCover.vue'
import { useCartStore } from '@/stores/cart'
import type { CataVO, CourseBaseInfoVO, CourseTeacherVO } from '@/types/course'
import { COURSE_TYPES, GOAL_TYPES, TRAINING_LEVELS, labelOf } from '@/types/course'
import { formatDuration, formatPrice } from '@/utils/format'

/**
 * 课程详情页
 *
 * 三个接口并行取（`Promise.all`）：基本信息 / 训练目录 / 教练。
 * 它们之间没有依赖，串行取会让首屏多等两轮往返。
 */
const route = useRoute()
const router = useRouter()
const cart = useCartStore()

const courseId = computed(() => String(route.params.id ?? ''))

const loading = ref(true)
const failed = ref(false)
/** 加购 / 报名进行中（防连点：后端虽然幂等，但重复点击会让角标数字来回跳） */
const adding = ref(false)
const course = ref<CourseBaseInfoVO | null>(null)
const catalogues = ref<CataVO[]>([])
const teachers = ref<CourseTeacherVO[]>([])

const courseTypeLabel = computed(() => labelOf(COURSE_TYPES, course.value?.courseType))
const goalLabel = computed(() => labelOf(GOAL_TYPES, course.value?.goalType))
const levelLabel = computed(() => labelOf(TRAINING_LEVELS, course.value?.trainingLevel))
const priceText = computed(() => formatPrice(course.value?.price, course.value?.free))

/**
 * 是不是免费课。
 *
 * ⚠️ 详情接口的 `free` 是**数字**（1 免费 / 2 付费），不是布尔 ——
 * 同一个字段在搜索接口里是 Boolean、在详情接口里是 Integer（后端既有的不一致），
 * 写判断时必须认准当前用的是哪个接口。这里再兜一层 `price === 0`。
 */
const isFree = computed(() => course.value?.free === 1 || course.value?.price === 0)

/** 训练动作总数（取目录里 type=2 的节点数，与接口的 sectionNum 互相印证） */
const actionCount = computed(() =>
  catalogues.value.reduce((sum, day) => sum + (day.sections?.length || 0), 0),
)

onMounted(async () => {
  loading.value = true
  try {
    const [info, catas, ts] = await Promise.all([
      getCourseBaseInfo(courseId.value),
      getCourseCatalogues(courseId.value),
      getCourseTeachers(courseId.value),
    ])
    course.value = info
    catalogues.value = catas || []
    teachers.value = ts || []
    // ★ 必须自己判一遍：baseInfo 是**管理端语义**的接口，未上架的课程也会原样返回
    //   （status 2-已上架 / 3-下架 / 4-已完结；fromDraft=true 表示还在编辑态）。
    //   少了这道判断，用户手输一个草稿课程的 id 就能看到未上架内容。
    failed.value = !info || info.status !== 2 || info.fromDraft === true
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
})

/**
 * 主按钮：付费课加入购物车 / 免费课直接报名。
 *
 * ★ 分成两条路不是前端自作主张 —— 后端 `validateCourses` 明确拒绝免费课下单
 *   （原话是"免费课不该走下单，直接报名，否则会造出一堆 0 元订单"）。
 *   如果这里不分，用户点"加入购物车"只会收到一条后端报错，
 *   而这本来是可以避免的：页面右上角明明写着"免费"。
 */
async function handleAddCart() {
  if (!course.value) return
  adding.value = true
  try {
    if (isFree.value) {
      await enrollLesson(course.value.id)
      ElMessage.success('已加入我的课程')
    } else {
      await cart.add(course.value.id)
      ElMessage.success('已加入购物车')
    }
  } catch {
    /* 已由 request.ts 统一提示（例如"你已购买过该课程"） */
  } finally {
    adding.value = false
  }
}
</script>

<template>
  <div v-loading="loading" class="fm-page">
    <el-button class="back" text :icon="ArrowLeft" @click="router.back()">返回</el-button>

    <el-result
      v-if="!loading && (failed || !course)"
      icon="warning"
      title="课程不存在或已下架"
      sub-title="它可能刚被下架，返回列表看看别的课程"
    >
      <template #extra>
        <el-button type="primary" @click="router.push({ name: 'courses' })">去课程列表</el-button>
      </template>
    </el-result>

    <template v-else-if="course">
      <el-card shadow="never" class="head-card">
        <div class="head">
          <CourseCover class="head-cover" :cover-url="course.coverUrl" :name="course.name" />

          <div class="head-info">
            <h1 data-testid="course-detail-title">{{ course.name }}</h1>

            <div class="head-tags">
              <el-tag v-if="courseTypeLabel" type="warning" effect="dark" size="small">
                {{ courseTypeLabel }}
              </el-tag>
              <el-tag v-if="goalLabel" type="warning" effect="plain" size="small">{{ goalLabel }}</el-tag>
              <el-tag v-if="levelLabel" effect="plain" size="small">{{ levelLabel }}</el-tag>
              <el-tag v-if="course.free === 1" type="success" effect="plain" size="small">免费</el-tag>
            </div>

            <div class="head-stats">
              <div class="stat">
                <span class="stat-label">价格</span>
                <span class="stat-value price" data-testid="course-detail-price">{{ priceText }}</span>
              </div>
              <div class="stat">
                <span class="stat-label">训练动作</span>
                <!-- ⚠️ `CourseBaseInfoVO` 没有 sectionNum 字段（那是 CourseFullDTO 的），
                     所以这里直接数目录里的动作节点 -->
                <span class="stat-value" data-testid="course-detail-sections">{{ actionCount }} 个</span>
              </div>
              <div class="stat">
                <span class="stat-label">训练周期</span>
                <span class="stat-value">{{ course.totalDays ? `${course.totalDays} 天` : '—' }}</span>
              </div>
              <div class="stat">
                <span class="stat-label">每周频次</span>
                <span class="stat-value">{{ course.weeklyFrequency ? `${course.weeklyFrequency} 次` : '—' }}</span>
              </div>
              <div class="stat">
                <span class="stat-label">课程有效期</span>
                <span class="stat-value">{{ course.validDuration ? `${course.validDuration} 天` : '—' }}</span>
              </div>
            </div>

            <div class="head-actions">
              <el-button
                type="primary"
                size="large"
                :icon="ShoppingCart"
                :loading="adding"
                data-testid="course-buy"
                @click="handleAddCart"
              >
                {{ isFree ? '免费学习' : '加入购物车' }}
              </el-button>
              <span v-if="!isFree" class="action-hint">结算时可选优惠券</span>
            </div>
          </div>
        </div>
      </el-card>

      <el-card shadow="never" class="section-card">
        <template #header><div class="fm-card-title">训练目录</div></template>

        <el-empty v-if="!catalogues.length" description="这门课还没有配置训练目录" :image-size="80" />

        <el-collapse v-else data-testid="course-catalog">
          <el-collapse-item
            v-for="(day, di) in catalogues"
            :key="day.id"
            :name="day.id"
          >
            <template #title>
              <span class="day-title">
                <strong>{{ day.name }}</strong>
                <el-tag size="small" type="info" effect="plain">{{ day.sections?.length || 0 }} 个动作</el-tag>
                <el-tag v-if="day.mediaDuration" size="small" type="info" effect="plain">
                  {{ formatDuration(day.mediaDuration) }}
                </el-tag>
              </span>
            </template>

            <el-table :data="day.sections || []" size="small" class="action-table">
              <el-table-column type="index" label="#" width="50" />
              <el-table-column prop="name" label="动作" min-width="140" />
              <el-table-column label="组数 × 次数" width="130">
                <template #default="{ row }">
                  <span v-if="row.sets && row.reps">{{ row.sets }} × {{ row.reps }}</span>
                  <span v-else-if="row.durationSeconds">{{ row.durationSeconds }} 秒</span>
                  <span v-else>—</span>
                </template>
              </el-table-column>
              <el-table-column label="休息" width="90">
                <template #default="{ row }">
                  {{ row.restSeconds ? `${row.restSeconds} 秒` : '—' }}
                </template>
              </el-table-column>
              <el-table-column label="负重" width="90">
                <template #default="{ row }">
                  {{ row.weight ? `${row.weight} kg` : '自重' }}
                </template>
              </el-table-column>
              <el-table-column prop="targetMuscles" label="目标肌群" min-width="140" />
              <el-table-column label="视频" width="90">
                <template #default="{ row }">
                  <el-tag v-if="row.mediaId" size="small" effect="plain">已配</el-tag>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
            </el-table>

            <div v-if="di === 0" class="day-note">
              ⓘ 详情页只读展示；跟练与进度上报在 M7-4 的学习页。
            </div>
          </el-collapse-item>
        </el-collapse>
      </el-card>

      <el-card shadow="never" class="section-card">
        <template #header><div class="fm-card-title">课程说明</div></template>

        <el-descriptions :column="1" border>
          <el-descriptions-item label="课程介绍">{{ course.courseIntroduce || '—' }}</el-descriptions-item>
          <el-descriptions-item label="适用人群">{{ course.usePeople || '—' }}</el-descriptions-item>
          <el-descriptions-item label="课程详情">{{ course.courseDetail || '—' }}</el-descriptions-item>
          <el-descriptions-item label="注意事项">{{ course.precautions || '—' }}</el-descriptions-item>
          <el-descriptions-item label="所需器械">{{ course.requiredEquipment || '—' }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card v-if="teachers.length" shadow="never" class="section-card">
        <template #header><div class="fm-card-title">授课教练</div></template>

        <div class="teacher-list" data-testid="course-detail-teachers">
          <div v-for="t in teachers" :key="t.id" class="teacher">
            <el-avatar :size="52" :src="t.icon ?? undefined">
              {{ (t.name || '?').slice(0, 1) }}
            </el-avatar>
            <div class="teacher-info">
              <div class="teacher-name">{{ t.name }}</div>
              <div class="teacher-intro">{{ t.intro || '这位教练还没写简介' }}</div>
            </div>
          </div>
        </div>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.back {
  margin-bottom: 10px;
}

.head-card {
  margin-bottom: 16px;
}

.head {
  display: flex;
  gap: 22px;
}

.head-cover {
  width: 320px;
  flex-shrink: 0;
  border-radius: var(--fm-radius);
}

.head-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.head-info h1 {
  margin: 0;
  font-size: 22px;
  line-height: 1.4;
}

.head-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.head-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 28px;
  padding: 12px 16px;
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.stat-value {
  font-size: 15px;
  font-weight: 600;
}

.stat-value.price {
  font-size: 20px;
  color: var(--el-color-primary);
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: auto;
}

.action-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.section-card {
  margin-bottom: 16px;
}

.day-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
}

.action-table {
  margin-top: 4px;
}

.day-note {
  margin-top: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.muted {
  color: var(--el-text-color-placeholder);
}

.teacher-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.teacher {
  display: flex;
  align-items: center;
  gap: 14px;
}

.teacher-info {
  min-width: 0;
}

.teacher-name {
  font-size: 15px;
  font-weight: 600;
}

.teacher-intro {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>
