<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import {
  addPlanDays,
  createPlan,
  getPlanDetail,
  getPlanDict,
  removePlanDay,
  removePlanItem,
  renamePlanDay,
  updatePlan,
} from '@/api/plan'
import DayCard from '@/components/plan/DayCard.vue'
import type { PlanDay, PlanDayItem, PlanDetail, PlanDict } from '@/types/plan'

/**
 * 计划编排（M11-2 / P0）
 *
 * 两种进入方式：
 *   · `/plan/edit/new`  → 先建一个空计划（计划名在这里问一次），再把地址换成 `/plan/edit/{id}`
 *   · `/plan/edit/:id`  → 直接编排已有计划
 *
 * ★ 新建时仍会先问一次名字（省得建完还要再改一次），但现在**改计划名有接口了**
 *   （`PUT /pls/plans/{id}`，M11-2 P0 补齐），页面顶部可以直接改名。
 *
 * ★「添加训练日」是一个**批量**接口（一次提交 [1,2,3,4,5]）：
 *   前端不要循环调五次 —— 循环会产生 5 个事务，第 4 个失败时前 3 个已经落库，
 *   用户看到"添加失败"但列表里多了 3 天。
 */
const route = useRoute()
const router = useRouter()

const routeId = computed(() => String(route.params.id ?? 'new'))
const planId = ref<string>(routeId.value === 'new' ? '' : routeId.value)

const loading = ref(true)
const errorText = ref('')
const detail = ref<PlanDetail | null>(null)
const dict = ref<PlanDict | null>(null)

const dialogVisible = ref(false)
const pickedWeekdays = ref<number[]>([])
const submitting = ref(false)

const days = computed(() => detail.value?.days ?? [])

/** 已经用掉的星期：添加到弹层里要置灰，否则重复添加会被后端静默跳过（用户以为没生效） */
const usedWeekdays = computed(() => days.value.map((d) => d.weekday).filter((w): w is number => w !== null))

async function bootstrap() {
  loading.value = true
  errorText.value = ''
  try {
    dict.value = await getPlanDict()
    if (routeId.value === 'new') {
      const title = await askPlanTitle()
      const id = await createPlan({ title: title || undefined, goal: dict.value.goals[0], difficulty: 0 })
      planId.value = id
      // 换成真实 id：刷新页面才不会又建一个新计划
      await router.replace(`/plan/edit/${id}`)
    }
    detail.value = await getPlanDetail(planId.value)
  } catch (e) {
    errorText.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

/** 问计划名；用户取消 = 用后端默认的「未命名计划」 */
async function askPlanTitle(): Promise<string> {
  try {
    const { value } = await ElMessageBox.prompt('给这个计划起个名字（留空则叫「未命名计划」）', '新建计划', {
      confirmButtonText: '创建',
      cancelButtonText: '用默认名',
      inputPlaceholder: '例如：周一练胸',
      inputValidator: (v: string) => (v && v.length > 64 ? '最多 64 个字' : true),
    })
    return (value || '').trim()
  } catch {
    return ''
  }
}

onMounted(bootstrap)

async function reload() {
  try {
    detail.value = await getPlanDetail(planId.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '刷新失败')
  }
}

function openAddDay() {
  pickedWeekdays.value = []
  dialogVisible.value = true
}

async function confirmAddDay() {
  if (pickedWeekdays.value.length === 0) {
    ElMessage.warning('至少选一天')
    return
  }
  submitting.value = true
  try {
    await addPlanDays(planId.value, pickedWeekdays.value)
    dialogVisible.value = false
    await reload()
    ElMessage.success('训练日已添加')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '添加失败')
  } finally {
    submitting.value = false
  }
}

/** 重命名：不传 = 保持原值（后端刻意不加 @NotBlank） */
async function handleRename(day: PlanDay) {
  try {
    const { value } = await ElMessageBox.prompt('训练日名字', '重命名', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValue: day.name,
      inputValidator: (v: string) => {
        if (!v || !v.trim()) return '名字不能为空'
        return v.length > 32 ? '最多 32 个字' : true
      },
    })
    await renamePlanDay(day.id, String(value).trim())
    await reload()
  } catch (e) {
    // 用户点取消 → ElMessageBox reject，必须吞掉；接口报错才提示
    if (e instanceof Error && e.message) {
      ElMessage.error(e.message)
    }
  }
}

/** 改计划名（走 PUT /pls/plans/{id}；官方计划后端会拒） */
async function handleRenamePlan() {
  if (!detail.value) return
  try {
    const { value } = await ElMessageBox.prompt('计划名', '修改计划名', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValue: detail.value.title,
      inputValidator: (v: string) => {
        if (!v || !v.trim()) return '计划名不能为空'
        return v.length > 64 ? '最多 64 个字' : true
      },
    })
    await updatePlan(planId.value, { title: String(value).trim() })
    await reload()
    ElMessage.success('已改名')
  } catch (e) {
    if (e instanceof Error && e.message) {
      ElMessage.error(e.message)
    }
  }
}

/**
 * 删除训练日（连同它下面的动作项）。
 *
 * ⚠️ **已经练过的那一天删不掉**：后端返回 400 + "「周一练胸」已经练过 2 次…"。
 * 所以这里不预先禁用按钮 —— 让用户点、让后端的原话弹出来，
 * 比前端猜"这一天能不能删"可靠（前端猜错就是"按钮点了没反应"）。
 */
async function handleRemoveDay(day: PlanDay) {
  try {
    await ElMessageBox.confirm(
      `删除「${day.name}」？它下面的 ${day.items.length} 个动作会一起删掉。`,
      '删除训练日',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await removePlanDay(day.id)
    await reload()
    ElMessage.success('训练日已删除')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

/** 点某一天 → 进动作库挑动作（中途退出不落库，确认时才提交） */
function pickExercises(day: PlanDay) {
  router.push({ path: '/plan/exercise-lib', query: { dayId: day.id, planId: planId.value } })
}

async function handleRemoveItem(day: PlanDay, item: PlanDayItem) {
  try {
    await ElMessageBox.confirm(`确定把「${item.exerciseName}」从「${day.name}」里移除吗？`, '移除动作', {
      type: 'warning',
      confirmButtonText: '移除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await removePlanItem(day.id, item.id)
    await reload()
    ElMessage.success('已移除')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '移除失败')
  }
}

function goDetail() {
  router.push(`/plan/detail/${planId.value}`)
}
</script>

<template>
  <div class="plan-edit">
    <div class="page-head">
      <div>
        <div class="page-title">编排训练日</div>
        <div class="page-sub">
          {{ detail?.title || '未命名计划' }}
          <el-button link type="primary" size="small" @click="handleRenamePlan">改名</el-button>
        </div>
      </div>
      <el-button type="primary" plain @click="goDetail">完成编排</el-button>
    </div>

    <el-skeleton v-if="loading" :rows="5" animated />

    <el-alert v-else-if="errorText" type="error" :title="errorText" :closable="false" show-icon>
      <template #default>
        <el-button size="small" @click="bootstrap">重试</el-button>
      </template>
    </el-alert>

    <template v-else>
      <el-empty
        v-if="days.length === 0"
        description="还没有训练日，先选每周练哪几天"
        :image-size="80"
      >
        <el-button type="primary" @click="openAddDay">去添加</el-button>
      </el-empty>

      <template v-else>
        <div class="hint">点某一天进去挑动作；「未命名训练日」可以在右边改名；选错星期几可以删掉重加</div>

        <div v-for="day in days" :key="day.id" class="day-wrap">
          <div class="day-ops">
            <el-button size="small" @click="handleRename(day)">重命名</el-button>
            <el-button size="small" type="primary" plain @click="pickExercises(day)">挑动作</el-button>
            <el-button size="small" type="danger" plain @click="handleRemoveDay(day)">删除</el-button>
          </div>
          <DayCard
            :day="day"
            editable
            @add-items="pickExercises"
            @remove-item="handleRemoveItem"
          />
        </div>
      </template>

      <div class="bottom-bar">
        <el-button size="large" @click="openAddDay">添加训练日</el-button>
        <el-button type="primary" size="large" @click="goDetail">完成编排</el-button>
      </div>
    </template>

    <!-- 添加训练日弹层：周一~周日多选 -->
    <el-dialog v-model="dialogVisible" title="添加训练日" width="420px">
      <div class="dialog-tip">选每周练哪几天，一天对应一个训练日</div>
      <el-checkbox-group v-model="pickedWeekdays" class="weekday-group">
        <el-checkbox
          v-for="w in dict?.weekdays ?? []"
          :key="String(w.value)"
          :value="Number(w.value)"
          :disabled="usedWeekdays.includes(Number(w.value))"
          border
        >
          {{ w.label }}
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="confirmAddDay">添加到列表</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.plan-edit {
  max-width: 880px;
  margin: 0 auto;
  padding-bottom: 84px;
}

.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
}

.page-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}

.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 10px;
}

.day-wrap {
  position: relative;
}

.day-ops {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 6px;
}

.bottom-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  gap: 12px;
  justify-content: center;
  padding: 12px 24px;
  background: #fff;
  border-top: 1px solid var(--el-border-color-light);
  z-index: 20;
}

.dialog-tip {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 12px;
}

.weekday-group {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
