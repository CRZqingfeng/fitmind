<script setup lang="ts">
import { ArrowLeft, ArrowRight, CircleCheck, Delete, Plus, RefreshRight, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { getAllCategories } from '@/api/category'
import {
  checkBeforeUpShelf,
  checkCourseName,
  getCourseBaseInfo,
  getCourseCatas,
  getCourseSubjects,
  getCourseTeachers,
  saveCourseBaseInfo,
  saveCourseCatas,
  saveCourseMedia,
  saveCourseSubjects,
  saveCourseTeachers,
  upShelf,
} from '@/api/course'
import { confirmUpload, uploadFile } from '@/api/media'
import { getSubjectPage } from '@/api/subject'
import { findUserIdByPhone, getCoaches } from '@/api/user'
import type { CategoryVO, CourseTeacherVO, SubjectVO } from '@/types/admin'
import { ACTION_TYPE_OPTIONS, DIFFICULTY_OPTIONS, SUBJECT_TYPE_OPTIONS } from '@/types/admin'
import { formatDuration } from '@/utils/format'
import { readVideoDuration } from '@/utils/media-upload'
import { uploadResumable } from '@/utils/chunk-upload'
import { ApiError } from '@/utils/request'

/**
 * 建课向导（M7-12 第 2 期新增）
 *
 * ============================================================
 * ★★ 为什么是 **5 步**（不是 M7 方案 §7.2 写的"四步"）
 * ============================================================
 * `CourseConstants.CourseStep`：BASE_INFO(1) → CATALOGUE(2) → MEDIA(3) → SUBJECT(4) → TEACHER(5)，
 * 而 `REQUIRED_FOR_SHELF = TEACHER` —— **教练是上架的硬门槛**。
 *
 * ============================================================
 * ★★ 全过程的顺序**不能随意调**，有一条硬约束
 * ============================================================
 * **题目只能在"草稿态"配**：建题 / 绑题都校验 `course_draft`，
 * 而 `upShelf` 会把草稿整份删掉 → **上架后再也改不了题**。
 * 所以第 4 步（配题）必须排在第 5 步与"上架"之前，且 UI 要**如实说明**这件事，
 * 而不是给一个点了会报错的按钮。
 *
 * ============================================================
 * ★★ 每一步都**立刻落库**（不是最后一次性提交）
 * ============================================================
 * 这不是"实现偷懒"，而是这套接口的形态决定的：后端把每一步的状态存在
 * `course_draft` 里，`POST /catas/save/{id}/2` 这类接口本身就是"保存这一步 + 推进进度"。
 * 好处很实在：**用户中途去题库页建题、再回来继续填，进度不丢**（所以第 4 步
 * 才敢直接给"去题库新建"的跳转，而不必在这里内联再写一套建题表单）。
 */
const route = useRoute()
const router = useRouter()

/** 课程 id：**新建时为空**，第 1 步保存后由后端返回（后续所有步骤的 key） */
const courseId = ref('')
const courseName = ref('')
const currentStep = ref(1)
const loading = ref(false)
const saving = ref(false)
const isEdit = computed(() => !!route.params.id)

// ==================== 第 1 步：基本信息 ====================

const baseForm = reactive({
  name: '',
  courseType: 1,
  coverUrl: '',
  /** 封面预览（`coverUrl` 已是可匿名读的地址，直接用） */
  coverPreview: '',
  /** 级联选中的路径（长度 1~3，对应一级/二级/三级） */
  catePath: [] as string[],
  free: 1,
  /** 界面用**元**，提交时转**分**（后端字段是分） */
  priceYuan: 0,
  validDuration: 90,
  goalType: undefined as number | undefined,
  trainingLevel: undefined as number | undefined,
  totalDays: undefined as number | undefined,
  weeklyFrequency: undefined as number | undefined,
  equipment: '',
  estimatedCalories: undefined as number | undefined,
  enrollRange: [] as string[],
  courseIntroduce: '',
  usePeople: '',
  courseDetail: '',
  precautions: '',
  requiredEquipment: '',
})

const categories = ref<CategoryVO[]>([])
const cascaderProps = { value: 'id', label: 'name', children: 'children', checkStrictly: true, emitPath: true }

/**
 * 把后端的分类树转成 `el-cascader` 认的 option。
 *
 * ★ 为什么必须转：后端的 `children` 是 **`CategoryVO[] | null`**（叶子节点给 null），
 *   而 cascader 的 `CascaderOption.children` 只接受 `undefined` ——
 *   直接塞进去 TS 会报 `Type 'null' is not assignable to type 'CascaderOption[] | undefined'`。
 *   顺手把空数组也抹掉（`children: []` 会让 cascader 渲染出一个空的展开箭头）。
 */
interface CateOption {
  id: string
  name: string
  children?: CateOption[]
  /** `el-cascader` 的 `CascaderOption` 带索引签名，补上以通过类型检查 */
  [key: string]: unknown
}
function toCascaderOptions(list: CategoryVO[]): CateOption[] {
  return list.map((c) => ({
    id: c.id,
    name: c.name,
    ...(c.children?.length ? { children: toCascaderOptions(c.children) } : {}),
  }))
}
const cascaderOptions = computed(() => toCascaderOptions(categories.value ?? []))

async function loadCategories() {
  try {
    categories.value = (await getAllCategories()) ?? []
  } catch {
    /* 已提示 */
  }
}

/** 名称重名检查（失焦时；编辑态必须带上 id，否则后端不排除自己 → 改个名都说过） */
async function handleCheckName() {
  const name = baseForm.name.trim()
  if (!name || name === courseName.value) return
  try {
    const res = await checkCourseName(name, courseId.value || undefined)
    if (res?.exist) {
      ElMessage.warning(`课程名「${name}」已存在，请换一个（后端要求唯一）`)
    }
  } catch {
    /* 查不通不挡流程，交给保存时的后端校验 */
  }
}

/** 上传封面 —— ★ 走 `/ms/files?bizType=course-cover`，**不走**预签名媒资链路 */
const coverUploading = ref(false)
/** `<script setup>` 里没有 `$refs`，用同名 ref 变量接原生 input（Vue 会自动绑定） */
const coverInput = ref<HTMLInputElement>()

/**
 * 触发某个动作的"选视频"。
 *
 * ★ 这里用 `document.getElementById` 而不是"函数 ref 收进一个 map"：
 *   行是 `v-for` 动态渲染的，函数 ref 的类型签名（`Element | ComponentPublicInstance | null`）
 *   在模板里做窄化很容易写歪；id 查找简单、类型干净，而且这些 input 都在本组件内、id 唯一。
 */
function pickVideo(cataId: string) {
  const el = document.getElementById(`video-input-${cataId}`) as HTMLInputElement | null
  el?.click()
}

async function handleCoverChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  coverUploading.value = true
  try {
    const uploaded = await uploadFile(file, 'course-cover')
    /**
     * ★★ 存 `rawUrl`（不带签名）而**不是** `url`：
     *   `url` 带签名有有效期，存它的话封面会"过一会儿变破图"。
     *   `course-cover/` 前缀在 M8-1 的匿名读白名单里，所以去掉签名也能直接 `<img>`。
     */
    baseForm.coverUrl = uploaded.rawUrl
    baseForm.coverPreview = uploaded.rawUrl
    ElMessage.success('封面已上传')
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '封面上传失败')
  } finally {
    coverUploading.value = false
  }
}

function buildBaseBody(): Record<string, unknown> {
  return {
    ...(courseId.value ? { id: courseId.value } : {}),
    name: baseForm.name.trim(),
    courseType: baseForm.courseType,
    coverUrl: baseForm.coverUrl || undefined,
    firstCateId: baseForm.catePath[0] || undefined,
    secondCateId: baseForm.catePath[1] || undefined,
    thirdCateId: baseForm.catePath[2] || undefined,
    free: baseForm.free,
    price: baseForm.free === 1 ? 0 : Math.round(baseForm.priceYuan * 100),
    validDuration: baseForm.validDuration,
    goalType: baseForm.goalType,
    trainingLevel: baseForm.trainingLevel,
    totalDays: baseForm.totalDays,
    weeklyFrequency: baseForm.weeklyFrequency,
    equipment: baseForm.equipment || undefined,
    estimatedCalories: baseForm.estimatedCalories,
    enrollStartTime: baseForm.enrollRange[0] || undefined,
    enrollEndTime: baseForm.enrollRange[1] || undefined,
    courseIntroduce: baseForm.courseIntroduce || undefined,
    usePeople: baseForm.usePeople || undefined,
    courseDetail: baseForm.courseDetail || undefined,
    precautions: baseForm.precautions || undefined,
    requiredEquipment: baseForm.requiredEquipment || undefined,
  }
}

async function saveStep1(thenNext = true) {
  if (!baseForm.name.trim()) {
    ElMessage.warning('请填写课程名称')
    return false
  }
  if (baseForm.free === 2 && !(baseForm.priceYuan > 0)) {
    ElMessage.warning('付费课程请填写大于 0 的价格')
    return false
  }
  saving.value = true
  try {
    const saved = await saveCourseBaseInfo(buildBaseBody())
    courseId.value = saved?.id ?? courseId.value
    courseName.value = saved?.name ?? baseForm.name.trim()
    ElMessage.success(courseId.value ? `已保存（课程 id ${courseId.value}）` : '已保存')
    if (thenNext) currentStep.value = 2
    return true
  } catch {
    return false
  } finally {
    saving.value = false
  }
}

// ==================== 第 2 步：训练目录 ====================

interface SectionForm {
  id?: string
  name: string
}
interface DayForm {
  id?: string
  name: string
  sections: SectionForm[]
}

const days = ref<DayForm[]>([{ name: 'Day 1', sections: [{ name: '' }] }])

function addDay() {
  days.value.push({ name: `Day ${days.value.length + 1}`, sections: [{ name: '' }] })
}

function removeDay(idx: number) {
  if (days.value.length <= 1) {
    ElMessage.warning('至少保留一个训练日')
    return
  }
  days.value.splice(idx, 1)
}

function saveStep2() {
  if (!days.value.length) {
    ElMessage.warning('请至少添加一个训练日')
    return false
  }
  for (const [i, d] of days.value.entries()) {
    if (!d.name.trim()) {
      ElMessage.warning(`第 ${i + 1} 个训练日没有名字`)
      return false
    }
    d.sections = d.sections.filter((s) => s.name.trim() || s.id)
    if (!d.sections.length) {
      ElMessage.warning(`「${d.name}」下面还没有训练动作`)
      return false
    }
  }
  return true
}

async function commitStep2(thenNext = true) {
  if (!courseId.value) {
    ElMessage.warning('请先保存第 1 步（基本信息）')
    return false
  }
  if (!saveStep2()) return false
  saving.value = true
  try {
    /**
     * ★ 结构照 seed 的 `ensureCourse()` 抄：
     *   训练日 `{type:1, name, index:i+1, sections:[...]}`，
     *   而**训练动作不传 index**（`CataSaveDTO.index` 对动作没有意义，
     *   seed 里也没传 —— 位置由数组顺序决定）。
     */
    const payload = days.value.map((d, i) => ({
      ...(d.id ? { id: d.id } : {}),
      type: 1,
      name: d.name.trim(),
      index: i + 1,
      sections: d.sections.map((s) => ({
        ...(s.id ? { id: s.id } : {}),
        type: 2,
        name: s.name.trim(),
      })),
    }))
    await saveCourseCatas(courseId.value, 2, payload)
    ElMessage.success('训练目录已保存')
    // ★ 保存后必须**读回**：训练动作的 cataId 是后端生成的，第 3/4 步全靠它
    await loadCatas()
    if (thenNext) currentStep.value = 3
    return true
  } catch {
    return false
  } finally {
    saving.value = false
  }
}

// ==================== 第 3 步：训练视频 + 动作参数 ====================

interface ActionRow {
  cataId: string
  name: string
  dayName: string
  mediaId: string
  videoName: string
  mediaDuration: number
  trailer: boolean
  sets: number | undefined
  reps: number | undefined
  restSeconds: number | undefined
  weight: number | undefined
  actionType: number
  uploading: boolean
  percent: number
}

const actions = ref<ActionRow[]>([])

/** 读回目录树 → 摊平成"动作行"（第 3 步的操作对象） */
async function loadCatas() {
  if (!courseId.value) return
  const tree = (await getCourseCatas(courseId.value)) ?? []
  const rows: ActionRow[] = []
  for (const day of tree) {
    for (const act of day.sections ?? []) {
      rows.push({
        cataId: act.id,
        name: act.name,
        dayName: day.name,
        mediaId: act.mediaId ?? '',
        videoName: act.mediaName ?? '',
        mediaDuration: act.mediaDuration ?? 0,
        trailer: act.trailer ?? false,
        sets: act.sets ?? undefined,
        reps: act.reps ?? undefined,
        restSeconds: act.restSeconds ?? undefined,
        weight: act.weight ?? undefined,
        actionType: act.actionType ?? 1,
        uploading: false,
        percent: 0,
      })
    }
  }
  actions.value = rows
}

/**
 * 给某个动作上传训练视频（**M12-1 起改为分片上传**：init → 逐片 PUT → complete）。
 *
 * ★ 为什么换：课程视频动辄上百 MB，一次 PUT 在弱网下失败率高，且失败即从头再来。
 *   分片后失败只是"少几片"，重传会跳过已传的分片，同内容还能秒传命中。
 * ★ 视频落 `media/` 前缀（私有，播放必须签名）；封面仍走 `/ms/files` 服务端中转。
 * ★ 合并出 media 记录后再调一次 `confirmUpload` —— 它负责把**时长**写进库，
 *   分片上传本身不读视频元数据。
 */
async function handleVideoChange(e: Event, row: ActionRow) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  row.uploading = true
  row.percent = 0
  try {
    const result = await uploadResumable(file, {
      bizType: 'media',
      target: 'media',
      onProgress: (p) => (row.percent = p),
    })
    const mediaId = result.mediaId
    if (!mediaId) throw new Error('上传完成但没有返回媒资 id')
    const duration = await readVideoDuration(file)
    await confirmUpload(mediaId, { duration })
    row.mediaId = String(mediaId)
    row.videoName = file.name
    row.mediaDuration = duration || row.mediaDuration
    ElMessage.success(`「${row.name}」视频已上传（${formatDuration(row.mediaDuration)}）`)
  } catch (err) {
    ElMessage.error(err instanceof Error ? err.message : '视频上传失败')
  } finally {
    row.uploading = false
    row.percent = 0
  }
}

async function commitStep3(thenNext = true) {
  if (!courseId.value) {
    ElMessage.warning('请先保存第 1 步（基本信息）')
    return false
  }
  if (!actions.value.length) {
    ElMessage.warning('还没有训练动作，请先完成第 2 步')
    return false
  }
  saving.value = true
  try {
    await saveCourseMedia(
      courseId.value,
      actions.value.map((a) => ({
        cataId: a.cataId,
        mediaId: a.mediaId || undefined,
        videoName: a.videoName || undefined,
        mediaDuration: a.mediaDuration || undefined,
        trailer: a.trailer,
        sets: a.sets,
        reps: a.reps,
        restSeconds: a.restSeconds,
        weight: a.weight,
        actionType: a.actionType,
      })),
    )
    ElMessage.success('动作信息已保存')
    if (thenNext) currentStep.value = 4
    return true
  } catch {
    return false
  } finally {
    saving.value = false
  }
}

// ==================== 第 4 步：动作小测 ====================

/** cataId → 已绑题目 id 列表 */
const bindMap = reactive<Record<string, string[]>>({})
/** 该课程的题目池（弹窗里多选用） */
const subjectPool = ref<SubjectVO[]>([])
const subjectDialog = reactive({ visible: false, cataId: '', cataName: '', selected: [] as string[] })

async function loadBinds() {
  if (!courseId.value) return
  try {
    const list = (await getCourseSubjects(courseId.value)) ?? []
    for (const node of list) {
      bindMap[node.cataId] = (node.subjects ?? []).map((s) => s.id)
    }
  } catch {
    /* 已提示 */
  }
}

async function loadSubjectPool() {
  if (!courseId.value) return
  try {
    const page = await getSubjectPage({ pageNo: 1, pageSize: 100, courseId: courseId.value })
    subjectPool.value = page?.list ?? []
  } catch {
    /* 已提示 */
  }
}

async function openSubjectPicker(row: ActionRow) {
  subjectDialog.cataId = row.cataId
  subjectDialog.cataName = row.name
  subjectDialog.selected = [...(bindMap[row.cataId] ?? [])]
  subjectDialog.visible = true
  await loadSubjectPool()
}

async function confirmSubjectPicker() {
  saving.value = true
  try {
    bindMap[subjectDialog.cataId] = [...subjectDialog.selected]
    await commitBinds()
    subjectDialog.visible = false
    ElMessage.success('已绑定')
  } catch {
    /* 已提示 */
  } finally {
    saving.value = false
  }
}

/** 全量提交绑定（接口语义就是"全量覆盖"，所以每次都把所有节点发一遍） */
async function commitBinds() {
  if (!courseId.value) return
  const list = actions.value
    .map((a) => ({ cataId: a.cataId, subjectIds: bindMap[a.cataId] ?? [] }))
    .filter((item) => item.subjectIds.length > 0)
  if (!list.length) {
    ElMessage.warning('还没有任何节点绑定题目')
    return
  }
  await saveCourseSubjects(courseId.value, list)
}

async function commitStep4(thenNext = true) {
  const bound = Object.values(bindMap).filter((ids) => ids.length).length
  if (!bound) {
    /**
     * ⚠️ 这里**不拦截**（不 return false）：小测不是上架门槛
     *   （`REQUIRED_FOR_SHELF = TEACHER`，只要求走到第 5 步）。
     *   强行要求"必须配题"会让"先上架、回头再补"这条正当路径走不通。
     */
    ElMessage.info('这门课暂时没有配小测（不影响上架）')
  } else {
    saving.value = true
    try {
      await commitBinds()
      ElMessage.success('小测绑定已保存')
    } catch {
      return false
    } finally {
      saving.value = false
    }
  }
  if (thenNext) currentStep.value = 5
  return true
}

// ==================== 第 5 步：教练 ====================

interface TeacherRow {
  id: string
  name: string
  icon: string | null
  intro: string | null
  isShow: boolean
}

const teachers = ref<TeacherRow[]>([])
const teacherPhone = ref('')
const teacherSearching = ref(false)

/**
 * 按手机号查教练并加入列表。
 *
 * ★★ 为什么是"输手机号"而不是"下拉选教练"：全项目**没有"列出全部教练"的接口**。
 *   `GET /us/users/coaches` 的 `ids` 是**必填**（漏传 → HTTP 200 + `code 500`「系统繁忙」），
 *   它只能"按 id 批量取"。所以链路是两步：`手机号 → 查 id → 批量取详情`。
 *
 * ⚠️ `GET /us/users/ids` 返回的是**单个 id**（不是数组），且**查不到会抛业务异常**
 *   （后端 `AssertUtils.isNotNull`）→ 所以这里必须 try/catch，失败原因由后端给。
 */
async function addTeacherByPhone() {
  const phone = teacherPhone.value.trim()
  if (!/^1[3-9]\d{9}$/.test(phone)) {
    ElMessage.warning('请输入 11 位手机号')
    return
  }
  teacherSearching.value = true
  try {
    const id = await findUserIdByPhone(phone)
    if (!id) {
      ElMessage.warning('没找到这个手机号对应的用户')
      return
    }
    const found: CourseTeacherVO[] = (await getCoaches([String(id)])) ?? []
    const fresh = found.filter((f) => !teachers.value.some((t) => t.id === f.id))
    if (!fresh.length) {
      ElMessage.info('这位已经在列表里了')
      return
    }
    for (const f of fresh) {
      teachers.value.push({
        id: f.id,
        name: f.name ?? `用户 ${f.id}`,
        icon: f.icon,
        intro: f.intro,
        isShow: true,
      })
    }
    teacherPhone.value = ''
    ElMessage.success(`已加入 ${fresh.length} 位`)
  } catch (err) {
    // ★ 后端把"用户不存在"也做成了业务异常 —— 这里如实把原因显示出来，
    //   而不是吞掉它换一句"查询失败"（那正是本页面想避免的"点了没反应"）
    ElMessage.warning(err instanceof ApiError ? err.message : '查不到这个手机号对应的用户')
  } finally {
    teacherSearching.value = false
  }
}

/** 回显已绑教练（编辑草稿时） */
async function loadTeachers() {
  if (!courseId.value) return
  try {
    const list = (await getCourseTeachers(courseId.value)) ?? []
    teachers.value = list.map((t) => ({
      id: t.id,
      name: t.name ?? `用户 ${t.id}`,
      icon: t.icon,
      intro: t.intro,
      isShow: t.isShow ?? true,
    }))
  } catch {
    /* 已提示 */
  }
}

async function commitStep5(thenNext = true) {
  if (!courseId.value) {
    ElMessage.warning('请先保存第 1 步（基本信息）')
    return false
  }
  if (!teachers.value.length) {
    ElMessage.warning('至少要绑定 1 位教练 —— 这是上架的硬门槛')
    return false
  }
  saving.value = true
  try {
    await saveCourseTeachers({
      id: courseId.value,
      teachers: teachers.value.map((t) => ({ id: t.id, isShow: t.isShow })),
    })
    ElMessage.success('教练已保存')
    if (thenNext) ElMessage.info('5 步都完成了，可以上架了')
    return true
  } catch {
    return false
  } finally {
    saving.value = false
  }
}

// ==================== 上架 ====================

const upShelfing = ref(false)

async function handleUpShelf() {
  if (!courseId.value) return
  try {
    await ElMessageBox.confirm(
      '上架后会发生三件事：\n' +
        '① 草稿会被同步到正式表（并删除草稿）\n' +
        '② 发 MQ 通知搜索服务建索引（学员端能搜到）\n' +
        '③ ★ 题目将**无法再修改**（题目只存在于草稿态）\n\n确认上架？',
      '上架课程',
      { type: 'warning', confirmButtonText: '上架', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  upShelfing.value = true
  try {
    /**
     * ★ 先 `checkBeforeUpShelf` 再 `upShelf`：前者的价值是**把原因说出来**。
     *   直接调 `upShelf` 失败也会报错，但 `checkBeforeUpShelf` 是专门的"上架前体检"，
     *   能给出更具体的原因（如"存在没有视频的动作"）。
     *   ⚠️ 它是 `void`：成功 = 没抛异常，**不是**返回 `{canUpShelf:true}`。
     */
    await checkBeforeUpShelf(courseId.value)
    await upShelf(courseId.value)
    ElMessage.success('已上架')
    await router.replace({ name: 'courses' })
  } catch (err) {
    ElMessage.error(`还不能上架：${err instanceof Error ? err.message : '请检查课程内容'}`)
  } finally {
    upShelfing.value = false
  }
}

// ==================== 编辑态加载 ====================

async function loadCourse(id: string) {
  loading.value = true
  try {
    const info = await getCourseBaseInfo(id)
    courseId.value = info.id
    courseName.value = info.name
    baseForm.name = info.name ?? ''
    baseForm.courseType = info.courseType ?? 1
    baseForm.coverUrl = info.coverUrl ?? ''
    baseForm.coverPreview = info.coverUrl ?? ''
    baseForm.catePath = [info.firstCateId, info.secondCateId, info.thirdCateId].filter(
      (v): v is string => !!v,
    )
    baseForm.free = info.free ?? 1
    baseForm.priceYuan = info.price ? info.price / 100 : 0
    baseForm.validDuration = info.validDuration ?? 90
    baseForm.goalType = info.goalType ?? undefined
    baseForm.trainingLevel = info.trainingLevel ?? undefined
    baseForm.totalDays = info.totalDays ?? undefined
    baseForm.weeklyFrequency = info.weeklyFrequency ?? undefined
    baseForm.equipment = info.equipment ?? ''
    baseForm.estimatedCalories = info.estimatedCalories ?? undefined
    baseForm.courseIntroduce = info.courseIntroduce ?? ''
    baseForm.usePeople = info.usePeople ?? ''
    baseForm.courseDetail = info.courseDetail ?? ''
    baseForm.precautions = info.precautions ?? ''
    baseForm.requiredEquipment = info.requiredEquipment ?? ''
    if (info.enrollStartTime && info.enrollEndTime) {
      baseForm.enrollRange = [info.enrollStartTime, info.enrollEndTime]
    }

    // 目录回填（有目录才说明第 2 步做过）
    const tree = (await getCourseCatas(id)) ?? []
    if (tree.length) {
      days.value = tree.map((d) => ({
        id: d.id,
        name: d.name,
        sections: (d.sections ?? []).map((s) => ({ id: s.id, name: s.name })),
      }))
    }
    await loadCatas()
    await loadBinds()
    await loadTeachers()

    /**
     * ★ 从后端记录的进度决定"续填到哪一步"（`info.step`），而不是一律跳到第 1 步：
     *   编辑一门已填到第 3 步的草稿，用户想接着往下填，而不是从头看一遍。
     */
    currentStep.value = Math.min(Math.max(info.step ?? 1, 1), 5)
  } catch {
    /* 已提示 */
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadCategories()
  const id = route.params.id
  if (typeof id === 'string' && id) {
    await loadCourse(id)
  }
})

// ==================== 步骤切换 ====================

/**
 * 点步骤条跳转。
 *
 * ★ 第 1 步没保存（没有 courseId）之前**不许往后跳** ——
 *   后面的每一步都要用 `courseId` 做 key，跳过去只会得到一串报错。
 */
function handleStepClick(step: number) {
  if (step > 1 && !courseId.value) {
    ElMessage.warning('请先填写并保存课程基本信息')
    return
  }
  currentStep.value = step
}

const boundCount = computed(() => Object.values(bindMap).filter((ids) => ids.length).length)
const videoCount = computed(() => actions.value.filter((a) => a.mediaId).length)
</script>

<template>
  <div v-loading="loading" class="fm-page course-edit">
    <el-card shadow="never" class="head-card">
      <div class="head-row">
        <div>
          <h3 class="head-title">
            {{ isEdit ? '编辑课程' : '新建课程' }}
            <span v-if="courseName" class="head-sub">{{ courseName }}</span>
          </h3>
          <div class="head-meta">
            <span v-if="courseId" data-testid="edit-course-id">课程 id：{{ courseId }}</span>
            <span v-else class="muted">尚未保存（保存第 1 步后会生成课程 id）</span>
          </div>
        </div>
        <el-button :icon="RefreshRight" data-testid="edit-back" @click="router.push({ name: 'courses' })">
          返回列表
        </el-button>
      </div>

      <el-steps
        class="steps"
        :active="currentStep - 1"
        finish-status="success"
        align-center
        data-testid="edit-steps"
      >
        <el-step title="基本信息" description="名称 / 封面 / 分类 / 价格" @click="handleStepClick(1)" />
        <el-step title="训练目录" description="训练日 + 训练动作" @click="handleStepClick(2)" />
        <el-step title="训练视频" :description="`${videoCount}/${actions.length} 已挂视频`" @click="handleStepClick(3)" />
        <el-step title="动作小测" :description="`${boundCount} 个动作已配题`" @click="handleStepClick(4)" />
        <el-step title="课程教练" description="★ 上架门槛" @click="handleStepClick(5)" />
      </el-steps>
    </el-card>

    <!-- ==================== 第 1 步 ==================== -->
    <el-card v-show="currentStep === 1" shadow="never" class="step-card">
      <el-form label-width="110px" @submit.prevent="saveStep1(false)">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="课程名称" required>
              <el-input
                v-model="baseForm.name"
                placeholder="如：21 天增肌训练营"
                maxlength="60"
                show-word-limit
                data-testid="edit-name"
                @blur="handleCheckName"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="课程类型">
              <el-select v-model="baseForm.courseType" style="width: 100%" data-testid="edit-course-type">
                <el-option label="训练计划课" :value="1" />
                <el-option label="单动作教学" :value="2" />
                <el-option label="理论饮食课" :value="3" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="课程封面">
          <div class="cover-row">
            <img v-if="baseForm.coverPreview" :src="baseForm.coverPreview" class="cover-img" alt="封面" />
            <div v-else class="cover-empty">未上传</div>
            <div class="cover-ops">
              <input
                ref="coverInput"
                type="file"
                accept="image/*"
                style="display: none"
                data-testid="edit-cover-input"
                @change="handleCoverChange"
              />
              <el-button
                :icon="Upload"
                :loading="coverUploading"
                data-testid="edit-cover-upload"
                @click="coverInput?.click()"
              >
                {{ baseForm.coverUrl ? '重新上传' : '上传封面' }}
              </el-button>
              <div class="form-tip">
                走 <code>/ms/files?bizType=course-cover</code>，落 <code>course-cover/</code> 前缀
                （在匿名读白名单里，学员端能直接显示）。
              </div>
            </div>
          </div>
        </el-form-item>

        <el-form-item label="训练分类">
          <!--
            ⚠️ `el-cascader` / `el-date-picker` **不会把 `data-testid` 透传到根元素**
               （`el-input` / `el-select` / `el-button` 会）。所以这里包一层 div 带 testid，
               验收脚本才能稳定定位 —— 否则只能靠 `.el-cascader` 这类实现细节选择器。
          -->
          <div class="full-width" data-testid="edit-cate-wrap">
            <el-cascader
              v-model="baseForm.catePath"
              :options="cascaderOptions"
              :props="cascaderProps"
              clearable
              filterable
              placeholder="训练目标 / 训练部位 / 器械场景（可只选到第二级）"
              style="width: 100%"
            />
          </div>
        </el-form-item>

        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="是否免费">
              <el-radio-group v-model="baseForm.free" data-testid="edit-free">
                <el-radio :value="1" data-testid="edit-free-yes">免费</el-radio>
                <el-radio :value="2" data-testid="edit-free-no">付费</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="价格">
              <el-input-number
                v-model="baseForm.priceYuan"
                :min="0"
                :precision="2"
                :step="10"
                :disabled="baseForm.free === 1"
                data-testid="edit-price"
              />
              <span class="form-tip">元</span>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="有效期">
              <el-input-number v-model="baseForm.validDuration" :min="1" :max="3650" data-testid="edit-valid" />
              <span class="form-tip">天</span>
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">进阶信息（选填）</el-divider>

        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="训练目标">
              <el-select v-model="baseForm.goalType" clearable style="width: 100%" data-testid="edit-goal">
                <el-option label="增肌" :value="1" />
                <el-option label="减脂" :value="2" />
                <el-option label="塑形" :value="3" />
                <el-option label="保持" :value="4" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="适用水平">
              <el-select v-model="baseForm.trainingLevel" clearable style="width: 100%" data-testid="edit-level">
                <el-option label="零基础" :value="1" />
                <el-option label="初级" :value="2" />
                <el-option label="中级" :value="3" />
                <el-option label="高级" :value="4" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="周期天数">
              <el-input-number v-model="baseForm.totalDays" :min="1" :max="365" data-testid="edit-days" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="每周频次">
              <el-input-number v-model="baseForm.weeklyFrequency" :min="1" :max="14" data-testid="edit-freq" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="预计消耗">
              <el-input-number v-model="baseForm.estimatedCalories" :min="0" :max="5000" data-testid="edit-cal" />
              <span class="form-tip">千卡</span>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="所需器械">
              <el-input v-model="baseForm.equipment" placeholder="逗号分隔，如 哑铃,单杠" data-testid="edit-equipment" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="报名时间">
          <div class="full-width" data-testid="edit-enroll-wrap">
            <el-date-picker
              v-model="baseForm.enrollRange"
              type="datetimerange"
              value-format="YYYY-MM-DDTHH:mm:ss"
              start-placeholder="开始"
              end-placeholder="结束"
            />
          </div>
        </el-form-item>

        <el-form-item label="课程介绍">
          <el-input v-model="baseForm.courseIntroduce" type="textarea" :rows="2" data-testid="edit-intro" />
        </el-form-item>
        <el-form-item label="适合人群">
          <el-input v-model="baseForm.usePeople" type="textarea" :rows="2" data-testid="edit-usefor" />
        </el-form-item>
        <el-form-item label="课程详情">
          <el-input v-model="baseForm.courseDetail" type="textarea" :rows="3" data-testid="edit-detail" />
        </el-form-item>
        <el-form-item label="训练注意">
          <el-input v-model="baseForm.precautions" type="textarea" :rows="2" data-testid="edit-precautions" />
        </el-form-item>
      </el-form>
    </el-card>

    <!-- ==================== 第 2 步 ==================== -->
    <el-card v-show="currentStep === 2" shadow="never" class="step-card">
      <el-alert type="info" :closable="false" show-icon class="step-alert">
        <template #title>
          目录树是<strong>训练日 → 训练动作</strong>两级。保存是<strong>全量覆盖</strong>：
          每次保存都提交完整结构，删掉的动作会一并消失。
        </template>
      </el-alert>

      <div v-for="(day, di) in days" :key="di" class="day-block" data-testid="edit-day-block">
        <div class="day-head">
          <span class="day-index">训练日 {{ di + 1 }}</span>
          <el-input
            v-model="day.name"
            placeholder="训练日名称，如 Day 1 胸部+三头"
            class="day-name"
            :data-testid="`edit-day-name-${di + 1}`"
          />
          <el-button link type="danger" :icon="Delete" :data-testid="`edit-day-del-${di + 1}`" @click="removeDay(di)">
            删除本日
          </el-button>
        </div>

        <div v-for="(sec, si) in day.sections" :key="si" class="section-row">
          <span class="section-no">{{ di + 1 }}.{{ si + 1 }}</span>
          <el-input
            v-model="sec.name"
            placeholder="训练动作名，如 哑铃卧推"
            :data-testid="`edit-action-${di + 1}-${si + 1}`"
          />
          <el-button
            link
            type="danger"
            :icon="Delete"
            :data-testid="`edit-action-del-${di + 1}-${si + 1}`"
            @click="day.sections.splice(si, 1)"
          />
        </div>

        <el-button
          link
          type="primary"
          :icon="Plus"
          :data-testid="`edit-action-add-${di + 1}`"
          @click="day.sections.push({ name: '' })"
        >
          加一个训练动作
        </el-button>
      </div>

      <el-button class="add-day" :icon="Plus" data-testid="edit-day-add" @click="addDay">新增训练日</el-button>
    </el-card>

    <!-- ==================== 第 3 步 ==================== -->
    <el-card v-show="currentStep === 3" shadow="never" class="step-card">
      <el-alert type="info" :closable="false" show-icon class="step-alert">
        <template #title>
          给每个训练动作挂视频并设置动作参数。视频走<strong>预签名直传</strong>
          （与封面不同：封面走服务端中转，视频体积大走直传）。
        </template>
      </el-alert>

      <el-table :data="actions" data-testid="edit-action-table">
        <el-table-column label="训练日" prop="dayName" width="150" />
        <el-table-column label="训练动作" prop="name" min-width="150" data-testid="edit-action-name" />
        <el-table-column label="训练视频" min-width="260">
          <template #default="{ row }">
            <div class="video-cell">
              <span v-if="row.videoName" class="video-name" :data-testid="`edit-video-name-${row.cataId}`">
                {{ row.videoName }}
                <em v-if="row.mediaDuration">（{{ formatDuration(row.mediaDuration) }}）</em>
              </span>
              <span v-else class="muted">未上传</span>
              <input
                :id="`video-input-${row.cataId}`"
                type="file"
                accept="video/*"
                style="display: none"
                :data-testid="`edit-video-input-${row.cataId}`"
                @change="(e) => handleVideoChange(e, row as ActionRow)"
              />
              <el-button
                link
                type="primary"
                :loading="row.uploading"
                :data-testid="`edit-video-upload-${row.cataId}`"
                @click="pickVideo(row.cataId)"
              >
                {{ row.uploading ? `${row.percent}%` : row.mediaId ? '替换' : '上传' }}
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="组数" width="100">
          <template #default="{ row }">
            <el-input-number v-model="row.sets" :min="1" :max="99" controls-position="right" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="次数" width="100">
          <template #default="{ row }">
            <el-input-number v-model="row.reps" :min="1" :max="999" controls-position="right" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="休息(秒)" width="110">
          <template #default="{ row }">
            <el-input-number v-model="row.restSeconds" :min="0" :max="600" controls-position="right" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="负重(kg)" width="110">
          <template #default="{ row }">
            <el-input-number v-model="row.weight" :min="0" :max="500" :precision="1" controls-position="right" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-select v-model="row.actionType" size="small">
              <el-option v-for="t in ACTION_TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="可试看" width="90">
          <template #default="{ row }">
            <el-switch v-model="row.trailer" :data-testid="`edit-trailer-${row.cataId}`" />
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-tip">还没有训练动作 —— 请先回到第 2 步保存训练目录</div>
        </template>
      </el-table>
    </el-card>

    <!-- ==================== 第 4 步 ==================== -->
    <el-card v-show="currentStep === 4" shadow="never" class="step-card">
      <el-alert type="warning" :closable="false" show-icon class="step-alert">
        <template #title>
          ★ <strong>题目只能挂在草稿态的课程上</strong>：上架时后端会把草稿整份删除，
          所以<strong>上架之后就无法再修改题目</strong>。请在上架前配好。
        </template>
      </el-alert>

      <div class="pool-bar">
        <span class="muted">本课程的题目池：{{ subjectPool.length }} 道</span>
        <el-button link type="primary" data-testid="edit-goto-subjects" @click="router.push({ name: 'subjects' })">
          去题库页新建题目 →
        </el-button>
        <span class="form-tip">
          （每步都已落库，跳走再回来不会丢进度）
        </span>
      </div>

      <el-table :data="actions" data-testid="edit-bind-table">
        <el-table-column label="训练日" prop="dayName" width="150" />
        <el-table-column label="训练动作" prop="name" min-width="180" />
        <el-table-column label="已绑题目" min-width="120">
          <template #default="{ row }">
            <el-tag v-if="(bindMap[row.cataId] ?? []).length" type="success" size="small">
              {{ (bindMap[row.cataId] ?? []).length }} 道
            </el-tag>
            <span v-else class="muted">未配小测</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :data-testid="`edit-bind-${row.cataId}`"
              @click="openSubjectPicker(row as ActionRow)"
            >
              选择题目
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-tip">还没有训练动作 —— 请先完成第 2 步</div>
        </template>
      </el-table>
    </el-card>

    <!-- ==================== 第 5 步 ==================== -->
    <el-card v-show="currentStep === 5" shadow="never" class="step-card">
      <el-alert type="warning" :closable="false" show-icon class="step-alert">
        <template #title>
          <strong>教练是上架的硬门槛</strong>：后端 <code>REQUIRED_FOR_SHELF = TEACHER</code>，
          不绑定教练无法上架。
        </template>
      </el-alert>

      <div class="teacher-add">
        <el-input
          v-model="teacherPhone"
          placeholder="输入教练手机号（库里的演示教练：13800010002）"
          style="width: 340px"
          data-testid="edit-teacher-phone"
          @keyup.enter="addTeacherByPhone"
        />
        <el-button
          type="primary"
          :icon="Plus"
          :loading="teacherSearching"
          data-testid="edit-teacher-add"
          @click="addTeacherByPhone"
        >
          查询并加入
        </el-button>
        <span class="form-tip">
          没有"列出全部教练"的接口，所以只能按手机号查（先查 id，再批量取详情）。
        </span>
      </div>

      <el-table :data="teachers" class="teacher-table" data-testid="edit-teacher-table">
        <el-table-column label="教练" min-width="220">
          <template #default="{ row }">
            <div class="teacher-cell">
              <el-avatar :size="32" :src="row.icon ?? undefined" />
              <div>
                <div class="teacher-name" :data-testid="`edit-teacher-name-${row.id}`">{{ row.name }}</div>
                <div class="muted small">id {{ row.id }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="简介" prop="intro" min-width="200" show-overflow-tooltip />
        <el-table-column label="详情页展示" width="120">
          <template #default="{ row }">
            <el-switch v-model="row.isShow" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ $index }">
            <el-button link type="danger" :icon="Delete" @click="teachers.splice($index, 1)">移除</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="empty-tip">还没有绑定教练</div>
        </template>
      </el-table>
    </el-card>

    <!-- ==================== 底部操作栏 ==================== -->
    <el-card shadow="never" class="foot-card">
      <div class="foot-row">
        <el-button
          :icon="ArrowLeft"
          :disabled="currentStep === 1"
          data-testid="edit-prev"
          @click="currentStep -= 1"
        >
          上一步
        </el-button>

        <div class="foot-right">
          <el-button
            v-if="currentStep === 1"
            :loading="saving"
            data-testid="edit-save-1"
            @click="saveStep1(false)"
          >
            保存基本信息
          </el-button>
          <el-button
            v-if="currentStep === 2"
            :loading="saving"
            data-testid="edit-save-2"
            @click="commitStep2(false)"
          >
            保存训练目录
          </el-button>
          <el-button
            v-if="currentStep === 3"
            :loading="saving"
            data-testid="edit-save-3"
            @click="commitStep3(false)"
          >
            保存动作信息
          </el-button>
          <el-button
            v-if="currentStep === 4"
            :loading="saving"
            data-testid="edit-save-4"
            @click="commitStep4(false)"
          >
            保存小测绑定
          </el-button>
          <el-button
            v-if="currentStep === 5"
            :loading="saving"
            data-testid="edit-save-5"
            @click="commitStep5(false)"
          >
            保存教练
          </el-button>

          <el-button
            v-if="currentStep === 1"
            type="primary"
            :loading="saving"
            data-testid="edit-next-1"
            @click="saveStep1(true)"
          >
            保存并下一步
            <el-icon class="el-icon--right"><ArrowRight /></el-icon>
          </el-button>
          <el-button
            v-if="currentStep === 2"
            type="primary"
            :loading="saving"
            data-testid="edit-next-2"
            @click="commitStep2(true)"
          >
            保存并下一步
            <el-icon class="el-icon--right"><ArrowRight /></el-icon>
          </el-button>
          <el-button
            v-if="currentStep === 3"
            type="primary"
            :loading="saving"
            data-testid="edit-next-3"
            @click="commitStep3(true)"
          >
            保存并下一步
            <el-icon class="el-icon--right"><ArrowRight /></el-icon>
          </el-button>
          <el-button
            v-if="currentStep === 4"
            type="primary"
            :loading="saving"
            data-testid="edit-next-4"
            @click="commitStep4(true)"
          >
            保存并下一步
            <el-icon class="el-icon--right"><ArrowRight /></el-icon>
          </el-button>

          <el-button
            v-if="currentStep === 5"
            type="success"
            :icon="CircleCheck"
            :loading="upShelfing"
            data-testid="edit-upshelf"
            @click="handleUpShelf"
          >
            完成并上架
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- 选题目弹窗 -->
    <el-dialog
      v-model="subjectDialog.visible"
      :title="`给「${subjectDialog.cataName}」选题目`"
      width="620px"
      data-testid="edit-subject-dialog"
    >
      <el-alert v-if="!subjectPool.length" type="info" :closable="false" show-icon class="step-alert">
        <template #title>
          这门课还没有任何题目 —— 请先到「题库管理」建题，再回来绑定。
        </template>
      </el-alert>

      <el-checkbox-group v-model="subjectDialog.selected" class="subject-picker">
        <el-checkbox
          v-for="s in subjectPool"
          :key="s.id"
          :value="s.id"
          :data-testid="`edit-pick-subject-${s.id}`"
          class="subject-pick-item"
        >
          {{ s.name }}
          <el-tag size="small" class="ml">
            {{ SUBJECT_TYPE_OPTIONS.find((t) => t.value === s.subjectType)?.label }}
          </el-tag>
          <el-tag size="small" type="info" class="ml">
            {{ DIFFICULTY_OPTIONS.find((d) => d.value === s.difficulty)?.label }}
          </el-tag>
        </el-checkbox>
      </el-checkbox-group>

      <template #footer>
        <el-button data-testid="edit-subject-cancel" @click="subjectDialog.visible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="saving"
          :disabled="!subjectPool.length"
          data-testid="edit-subject-confirm"
          @click="confirmSubjectPicker"
        >
          确定（{{ subjectDialog.selected.length }} 道）
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head-card {
  margin-bottom: 16px;
}

.head-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.head-title {
  margin: 0;
  font-size: 18px;
}

.head-sub {
  margin-left: 10px;
  font-size: 14px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.head-meta {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.steps {
  margin-top: 18px;
}

.steps :deep(.el-step__head) {
  cursor: pointer;
}

.step-card {
  margin-bottom: 16px;
}

.step-alert {
  margin-bottom: 16px;
}

.cover-row {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}

.cover-img,
.cover-empty {
  width: 160px;
  height: 90px;
  border-radius: 6px;
  border: 1px solid var(--el-border-color-lighter);
  object-fit: cover;
}

.cover-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  background: var(--el-fill-color-lighter);
}

.day-block {
  padding: 12px 14px;
  margin-bottom: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.day-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.day-index {
  font-weight: 600;
  white-space: nowrap;
}

.day-name {
  max-width: 380px;
}

.section-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 0 8px 24px;
}

.section-no {
  width: 34px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.add-day {
  width: 100%;
}

.video-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.video-name {
  font-size: 12px;
}

.video-name em {
  color: var(--el-text-color-secondary);
  font-style: normal;
}

.teacher-add {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.teacher-table {
  margin-top: 6px;
}

.teacher-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.teacher-name {
  font-weight: 500;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.small {
  font-size: 11px;
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

.pool-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.foot-card {
  position: sticky;
  bottom: 0;
}

.foot-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.foot-right {
  display: flex;
  gap: 10px;
}

.subject-picker {
  display: flex;
  flex-direction: column;
  max-height: 380px;
  overflow-y: auto;
}

.subject-pick-item {
  height: auto;
  margin-bottom: 8px;
  white-space: normal;
}

.ml {
  margin-left: 6px;
}

.full-width {
  width: 100%;
}
</style>
