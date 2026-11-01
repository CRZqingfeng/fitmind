<script setup lang="ts">
import { Delete, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'

import { getCoursePage } from '@/api/course'
import { createSubject, deleteSubject, getSubject, getSubjectPage, updateSubject } from '@/api/subject'
import type { CoursePageVO, SubjectForm, SubjectVO } from '@/types/admin'
import {
  COURSE_STATUS,
  DIFFICULTY_OPTIONS,
  SUBJECT_TYPE,
  SUBJECT_TYPE_OPTIONS,
} from '@/types/admin'
import { pageTotal } from '@/utils/format'

/**
 * 题库管理（M7-12 第 2 期新增）
 *
 * ★★ 这一页的**全部设计**都服从一条后端硬约束：**题目只存在于「草稿态」**
 *   （`seed-demo-data.mjs:1015-1031` 实测撞出来的，M7 方案 §16.1 有完整记录）
 *
 *   | 环节 | 后端校验的表 | 已上架的课会怎样 |
 *   |---|---|---|
 *   | `POST /subjects` 建题 | `course_draft` | 报「课程不存在，请先保存课程基本信息」 |
 *   | `POST /courses/subjects/save/{id}` 绑题 | `course_draft` + `course_catalogue_draft` | 同上 |
 *   | `upShelf` 上架 | —— | **把草稿整份删掉** → 上架后再也改不了题 |
 *
 *   → 于是"课程下拉"**只列**两种状态：**待上架(1)** 与 **已下架(3)**
 *     （`CourseController.queryForPage` 的按状态路由：这两种都在草稿表里）。
 *     已上架的课**干脆不列** —— 让它出现在下拉里、选完再报错，
 *     等于把"后端约束"伪装成"这个页面有 bug"。
 *
 * ★ 题型的选项规则（`SubjectFormDTO` 的 `@Schema` 原文）：
 *   选择题 **2~10 个**选项；**判断题不传 `options`**（后端按 1-正确 / 2-错误 处理）。
 */
const loading = ref(false)
const route = useRoute()
const list = ref<SubjectVO[]>([])
/** ★ 用 number 而不是 string：后端 `total` 是字符串（Long→String 契约），
 *  但分页组件要的是数字 —— 统一从这里过 `pageTotal()`，避免散落 `Number()` 漏改 */
const total = ref(0)

const query = reactive({
  courseId: '',
  name: '',
  pageNo: 1,
  pageSize: 10,
})

/** 可配题的课程 = 草稿表里的课（待上架 + 已下架） */
const courses = ref<CoursePageVO[]>([])
const coursesLoaded = ref(false)

async function loadCourses() {
  try {
    /**
     * ★ 两次查询而不是"拉全部再前端过滤"：
     *   不传 `status` 时后端恒定返回 0 条（它查草稿表，而草稿在演示库里是空的），
     *   "全部"在这个接口上**不是一个可用的取值**（详见 types/admin.ts 的说明）。
     */
    const [draft, offShelf] = await Promise.all([
      getCoursePage({ pageNo: 1, pageSize: 100, status: COURSE_STATUS.DRAFT }),
      getCoursePage({ pageNo: 1, pageSize: 100, status: COURSE_STATUS.OFF_SHELF }),
    ])
    courses.value = [...(draft?.list ?? []), ...(offShelf?.list ?? [])]
  } catch {
    /* 已提示 */
  } finally {
    coursesLoaded.value = true
  }
}

async function load() {
  if (!query.courseId) {
    list.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const page = await getSubjectPage({
      pageNo: query.pageNo,
      pageSize: query.pageSize,
      courseId: query.courseId,
      name: query.name || undefined,
    })
    list.value = page?.list ?? []
    total.value = pageTotal(page)
  } catch {
    /* 已提示 */
  } finally {
    loading.value = false
  }
}

const currentCourse = computed(() => courses.value.find((c) => c.id === query.courseId))

// ==================== 新增 / 编辑 ====================

const OPTION_LABELS = ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J']
const MAX_OPTIONS = 10

const dialog = reactive({
  visible: false,
  mode: 'add' as 'add' | 'edit',
})
const submitting = ref(false)
const detailLoading = ref(false)

const form = reactive({
  id: '',
  courseId: '',
  name: '',
  subjectType: SUBJECT_TYPE.SINGLE as number,
  difficulty: 1,
  options: ['', ''] as string[],
  /** 答案序号（从 1 开始），与后端 `answers` 一致 */
  answers: [] as number[],
  analysis: '',
  score: 5,
})

/** 判断题没有"选项"，题型切换时要即时把界面换掉 */
const isJudge = computed(() => form.subjectType === SUBJECT_TYPE.JUDGE)
const isMulti = computed(
  () => form.subjectType === SUBJECT_TYPE.MULTI || form.subjectType === SUBJECT_TYPE.INDEFINITE,
)

/** 切题型时清空答案：单选留下的 2 个答案搬到判断题上会变成非法状态 */
function onTypeChange() {
  form.answers = []
  if (form.subjectType !== SUBJECT_TYPE.JUDGE && form.options.length < 2) {
    form.options = ['', '']
  }
}

function addOption() {
  if (form.options.length >= MAX_OPTIONS) {
    ElMessage.warning(`最多 ${MAX_OPTIONS} 个选项`)
    return
  }
  form.options.push('')
}

function removeOption(idx: number) {
  if (form.options.length <= 2) {
    ElMessage.warning('选择题至少要有 2 个选项')
    return
  }
  form.options.splice(idx, 1)
  // ★ 删掉选项后，比它大的答案序号要往前挪，否则会指向错的选项
  form.answers = form.answers
    .filter((a) => a !== idx + 1)
    .map((a) => (a > idx + 1 ? a - 1 : a))
}

/** 多选 / 不定向：勾选或取消某个选项（序号从 1 开始，与后端 `answers` 一致） */
function toggleAnswer(oneBased: number, checked: boolean) {
  form.answers = checked
    ? [...new Set([...form.answers, oneBased])].sort((a, b) => a - b)
    : form.answers.filter((a) => a !== oneBased)
}

async function openAdd() {
  dialog.mode = 'add'
  form.id = ''
  form.courseId = query.courseId
  form.name = ''
  form.subjectType = SUBJECT_TYPE.SINGLE
  form.difficulty = 1
  form.options = ['', '']
  form.answers = []
  form.analysis = ''
  form.score = 5
  dialog.visible = true
}

async function openEdit(row: SubjectVO) {
  dialog.mode = 'edit'
  form.id = row.id
  form.courseId = row.courseId ?? query.courseId
  dialog.visible = true
  detailLoading.value = true
  try {
    /**
     * ★ 必须**重新拉详情**再回填，不能用列表行：
     *   列表项虽然也含 options/answer，但编辑要的是 `answers`（序号数组），
     *   靠 `answer` 原文 split 会在"多选 + 中文逗号 + 空格"上翻车（后端两种形态都给，就用给好的）。
     */
    const detail = await getSubject(row.id)
    form.name = detail?.name ?? ''
    form.subjectType = detail?.subjectType ?? SUBJECT_TYPE.SINGLE
    form.difficulty = detail?.difficulty ?? 1
    form.options = detail?.options?.length ? [...detail.options] : ['', '']
    form.answers = detail?.answers?.length
      ? [...detail.answers]
      : String(detail?.answer ?? '')
          .split(',')
          .map((s) => Number(s.trim()))
          .filter((n) => Number.isFinite(n) && n > 0)
    form.analysis = detail?.analysis ?? ''
    form.score = detail?.score ?? 5
  } catch {
    dialog.visible = false
  } finally {
    detailLoading.value = false
  }
}

function buildPayload(): SubjectForm | null {
  const name = form.name.trim()
  if (!name) {
    ElMessage.warning('请填写题干')
    return null
  }
  if (!form.courseId) {
    ElMessage.warning('请选择所属课程')
    return null
  }

  let options: string[] | undefined
  if (!isJudge.value) {
    options = form.options.map((o) => o.trim())
    if (options.some((o) => !o)) {
      ElMessage.warning('选项内容不能为空（空选项请先删除）')
      return null
    }
    if (options.length < 2) {
      ElMessage.warning('选择题至少要有 2 个选项')
      return null
    }
  }

  if (!form.answers.length) {
    ElMessage.warning(isJudge.value ? '请选择正确答案（正确 / 错误）' : '请勾选正确答案')
    return null
  }
  if (!isMulti.value && form.answers.length > 1) {
    ElMessage.warning('单选题只能有 1 个正确答案')
    return null
  }

  const payload: SubjectForm = {
    courseId: form.courseId,
    name,
    subjectType: form.subjectType,
    difficulty: form.difficulty,
    // ★ 后端约定：选项序号从 1 开始，多个逗号分隔；判断题 1-正确 2-错误
    answer: [...form.answers].sort((a, b) => a - b).join(','),
    analysis: form.analysis.trim() || undefined,
    score: form.score,
  }
  // ★ 判断题**不传** options（传了后端会当成选择题处理）
  if (options) payload.options = options
  if (dialog.mode === 'edit') payload.id = form.id
  return payload
}

async function submit() {
  const payload = buildPayload()
  if (!payload) return
  submitting.value = true
  try {
    if (dialog.mode === 'add') {
      await createSubject(payload)
      ElMessage.success('题目已新增')
    } else {
      await updateSubject(form.id, payload)
      ElMessage.success('题目已更新')
    }
    dialog.visible = false
    await load()
  } catch {
    /* 已提示（含后端原文，如"课程不存在，请先保存课程基本信息"） */
  } finally {
    submitting.value = false
  }
}

async function handleDelete(row: SubjectVO) {
  try {
    await ElMessageBox.confirm(
      `确认删除这道题？\n\n${row.name.slice(0, 40)}${row.name.length > 40 ? '…' : ''}\n\n` +
        '注意：已被训练动作关联的题目，后端会拒绝删除（要先解绑）。',
      '删除题目',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteSubject(row.id)
    ElMessage.success('题目已删除')
    await load()
  } catch {
    /* 已提示 */
  }
}

onMounted(async () => {
  await loadCourses()
  /**
   * ★ 支持从「课程管理 → 配题」带 `?courseId=` 跳进来（少两步点击）。
   *   只有这个 id 确实在"可配题的课"里才采用 —— 否则会出现
   *   "下拉里没有这个选项、但表格却在查它"的怪状态（比如从一门已上架的课跳进来）。
   */
  const fromQuery = route.query.courseId
  if (typeof fromQuery === 'string' && fromQuery && courses.value.some((c) => c.id === fromQuery)) {
    query.courseId = fromQuery
    await load()
    return
  }
  /**
   * ★ 只有一门草稿课就自动选中 —— 少一次点击，且避免"表格空着让人以为坏了"。
   *   多门课时**不自动选**：选错课程建的题会算到别的课头上，让用户自己点。
   */
  if (courses.value.length === 1) {
    query.courseId = courses.value[0].id
    await load()
  }
})
</script>

<template>
  <div class="fm-page subject-admin">
    <el-card shadow="never" class="filter-card">
      <div class="filter-row">
        <el-select
          v-model="query.courseId"
          placeholder="选择课程（只列可配题的）"
          style="width: 300px"
          data-testid="subject-course"
          @change="query.pageNo = 1; load()"
        >
          <el-option
            v-for="c in courses"
            :key="c.id"
            :label="`${c.name}（${c.statusDesc ?? ''}）`"
            :value="c.id"
          />
        </el-select>

        <el-input
          v-model="query.name"
          placeholder="按题干搜索"
          clearable
          style="width: 220px"
          data-testid="subject-filter-name"
          @keyup.enter="query.pageNo = 1; load()"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>

        <el-button type="primary" :icon="Search" data-testid="subject-search" @click="query.pageNo = 1; load()">
          查询
        </el-button>
        <el-button
          type="primary"
          plain
          :icon="Plus"
          :disabled="!query.courseId"
          data-testid="subject-add"
          @click="openAdd"
        >
          新建题目
        </el-button>
        <el-button :icon="Refresh" data-testid="subject-refresh" @click="load">刷新</el-button>

        <span class="total-hint" data-testid="subject-total">共 {{ total }} 题</span>
      </div>

      <div class="hint-line">
        <p v-if="!coursesLoaded">正在加载可配题的课程…</p>
        <p v-else-if="!courses.length" class="warn">
          <strong>当前没有可配题的课程。</strong>
          题目只能挂在<strong>草稿态</strong>的课上 —— 请先到「课程管理 → 新建课程」建一门课
          （课建到第 1 步就会产生草稿），再回来配题。
        </p>
        <p v-else>
          课程下拉里只列 <strong>待上架 / 已下架</strong> 的课，因为<strong>题目只存在于草稿态</strong>：
          上架时后端会把草稿整份删掉，所以<strong>已上架的课无法再改题目</strong>。
        </p>
        <p>
          选项规则：选择题 <strong>2~10 个选项</strong>；判断题不填选项，答案就是"正确 / 错误"。
        </p>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" data-testid="subject-table">
        <el-table-column label="题干" min-width="300">
          <template #default="{ row }">
            <div class="subject-name" data-testid="subject-row-name">{{ row.name }}</div>
          </template>
        </el-table-column>

        <el-table-column label="题型" width="110">
          <template #default="{ row }">
            {{ SUBJECT_TYPE_OPTIONS.find((t) => t.value === row.subjectType)?.label ?? '—' }}
          </template>
        </el-table-column>

        <el-table-column label="难度" width="90">
          <template #default="{ row }">
            {{ DIFFICULTY_OPTIONS.find((d) => d.value === row.difficulty)?.label ?? '—' }}
          </template>
        </el-table-column>

        <el-table-column label="答案" width="90">
          <template #default="{ row }">{{ row.answer ?? '—' }}</template>
        </el-table-column>

        <el-table-column label="分值" width="80" prop="score" />

        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :icon="Edit"
              :data-testid="`subject-edit-${row.id}`"
              @click="openEdit(row as SubjectVO)"
            >
              编辑
            </el-button>
            <el-button
              link
              type="danger"
              :icon="Delete"
              :data-testid="`subject-delete-${row.id}`"
              @click="handleDelete(row as SubjectVO)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-tip" data-testid="subject-empty">
            {{ query.courseId ? '这门课还没有题目' : '请先选择课程' }}
          </div>
        </template>
      </el-table>

      <el-pagination
        v-if="total > query.pageSize"
        v-model:current-page="query.pageNo"
        class="pager"
        layout="prev, pager, next"
        :page-size="query.pageSize"
        :total="total"
        data-testid="subject-pager"
        @current-change="load"
      />
    </el-card>

    <el-dialog
      v-model="dialog.visible"
      :title="dialog.mode === 'add' ? `新建题目 · ${currentCourse?.name ?? ''}` : '编辑题目'"
      width="620px"
      data-testid="subject-dialog"
    >
      <el-form v-loading="detailLoading" label-width="90px" @submit.prevent="submit">
        <el-form-item label="所属课程" required>
          <el-select
            v-model="form.courseId"
            placeholder="选择课程"
            style="width: 100%"
            :disabled="dialog.mode === 'edit'"
            data-testid="subject-form-course"
          >
            <el-option
              v-for="c in courses"
              :key="c.id"
              :label="`${c.name}（${c.statusDesc ?? ''}）`"
              :value="c.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="题干" required>
          <el-input
            v-model="form.name"
            type="textarea"
            :rows="2"
            maxlength="255"
            show-word-limit
            placeholder="如：哑铃卧推时，肩胛骨应该怎么做？"
            data-testid="subject-form-name"
          />
        </el-form-item>

        <el-form-item label="题型" required>
          <el-radio-group v-model="form.subjectType" data-testid="subject-form-type" @change="onTypeChange">
            <el-radio-button
              v-for="t in SUBJECT_TYPE_OPTIONS"
              :key="t.value"
              :value="t.value"
              :data-testid="`subject-type-${t.value}`"
            >
              {{ t.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <!-- 选择题：选项列表 + 勾选答案 -->
        <template v-if="!isJudge">
          <el-form-item label="选项" required>
            <div class="option-list">
              <div v-for="(_, idx) in form.options" :key="idx" class="option-row">
                <el-checkbox
                  v-if="isMulti"
                  :model-value="form.answers.includes(idx + 1)"
                  :data-testid="`subject-answer-${idx + 1}`"
                  @change="(v) => toggleAnswer(idx + 1, v === true)"
                />
                <el-radio
                  v-else
                  :model-value="form.answers[0] ?? 0"
                  :value="idx + 1"
                  :data-testid="`subject-answer-${idx + 1}`"
                  @change="() => { form.answers = [idx + 1] }"
                >
                  <span class="opt-label">{{ OPTION_LABELS[idx] }}</span>
                </el-radio>

                <el-input
                  v-model="form.options[idx]"
                  placeholder="选项内容"
                  :data-testid="`subject-option-${idx + 1}`"
                />
                <el-button
                  link
                  type="danger"
                  :icon="Delete"
                  :data-testid="`subject-option-del-${idx + 1}`"
                  @click="removeOption(idx)"
                />
              </div>
              <el-button
                link
                type="primary"
                :icon="Plus"
                :disabled="form.options.length >= MAX_OPTIONS"
                data-testid="subject-option-add"
                @click="addOption"
              >
                加一个选项
              </el-button>
              <div class="form-tip">
                勾选/点选的即为正确答案（{{ isMulti ? '可多选' : '单选' }}）。
              </div>
            </div>
          </el-form-item>
        </template>

        <!-- 判断题：没有选项，答案就是正确 / 错误 -->
        <el-form-item v-else label="正确答案" required>
          <el-radio-group v-model="form.answers[0]" data-testid="subject-form-judge">
            <el-radio :value="1" data-testid="subject-judge-true">正确</el-radio>
            <el-radio :value="2" data-testid="subject-judge-false">错误</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="难度">
          <el-radio-group v-model="form.difficulty" data-testid="subject-form-difficulty">
            <el-radio-button
              v-for="d in DIFFICULTY_OPTIONS"
              :key="d.value"
              :value="d.value"
              :data-testid="`subject-difficulty-${d.value}`"
            >
              {{ d.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="分值">
          <el-input-number
            v-model="form.score"
            :min="1"
            :max="100"
            data-testid="subject-form-score"
          />
          <span class="form-tip">不填默认 5</span>
        </el-form-item>

        <el-form-item label="答案解析">
          <el-input
            v-model="form.analysis"
            type="textarea"
            :rows="2"
            placeholder="提交作答后才下发给学员"
            data-testid="subject-form-analysis"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button data-testid="subject-form-cancel" @click="dialog.visible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          data-testid="subject-form-submit"
          @click="submit"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.filter-card {
  margin-bottom: 16px;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.total-hint {
  margin-left: auto;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.hint-line {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--el-border-color-lighter);
  font-size: 12px;
  line-height: 1.9;
  color: var(--el-text-color-secondary);
}

.hint-line p {
  margin: 0;
}

.hint-line .warn {
  color: var(--el-color-warning);
}

.subject-name {
  font-weight: 500;
  line-height: 1.5;
}

.option-list {
  width: 100%;
}

.option-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.opt-label {
  margin-right: 2px;
  font-weight: 600;
}

.form-tip {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.empty-tip {
  padding: 20px 0;
  color: var(--el-text-color-secondary);
}

.pager {
  margin-top: 16px;
  justify-content: center;
}
</style>
