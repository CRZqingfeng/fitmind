/**
 * 课程管理与建课（fm-course，网关前缀 `/cs`）
 *
 * ★★ 管理端权限的**真实状态**（M7-12 第 2 期开工时实测，比注释更可信）：
 *
 *   `fm-course/src/main/resources/application.yml` 里 `fm.auth.resource.admin.enabled: true`，
 *   `pathPatterns` 逐条列了 18 条规则（课程写入 / 题目 / 分类写入）。
 *   为什么用 `METHOD:/path` 逐条列、而不是图省事写 `/courses/**`：
 *   `GET /courses/baseInfo/{id}`（学员端详情）与 `POST /courses/baseInfo/save`（管理端写入）
 *   **同前缀不同权** —— 纯路径 pattern 区分不了方法，会把学员端详情一起拦掉。
 *
 *   ⚠️ **但它当前是失效的**：工作区有一处**未提交改动**把
 *   `fm.auth.resource.enable` 从 `true` 改成了 `false`（注释还留着 `#true`），
 *   而 `ResourceInterceptorConfiguration.addInterceptors()` 在 `enable=false` 时
 *   **直接 return** → 后面的 admin 拦截器**压根没被注册**（哪怕 `admin.enabled: true`）。
 *   实测（2026-09-29）：学员 token 调 `GET /cs/courses/page` → **200**（本应 403）；
 *   `POST /cs/categorys/add` → **200 + code 400 参数校验**，说明已经过了权限关。
 *
 *   → 这不是前端能修的（改配置属后端，且那是别人的未提交改动，按约定不擅自动）。
 *     但**验收脚本里"学员越权应 403"那条负向断言会因此立不住** ——
 *     只能如实标注，绝不能假装通过。
 */
import type {
  CataSaveForm,
  CataSubjectVO,
  CataVO,
  CategoryVO,
  CourseBaseInfoVO,
  CourseMediaForm,
  CoursePageQuery,
  CoursePageVO,
  CourseSaveVO,
  CourseTeacherForm,
  CourseTeacherVO,
  NameExistVO,
  PageDTO,
} from '@/types/admin'
import { http } from '@/utils/request'

// ==================== 列表 / 上下架 / 删除 ====================

/** 课程分页（管理端） */
export function getCoursePage(query: CoursePageQuery): Promise<PageDTO<CoursePageVO>> {
  return http.get<PageDTO<CoursePageVO>>('/cs/courses/page', { params: query })
}

/** 课程基本信息（管理端；比用户端多正文四段，且 `fromDraft` 表示"是否在编辑态"） */
export function getCourseBaseInfo(id: string): Promise<CourseBaseInfoVO> {
  return http.get<CourseBaseInfoVO>(`/cs/courses/baseInfo/${id}`)
}

/**
 * 上架前校验
 *
 * ★★ 后端是 `void`：**校验失败会抛业务异常**（HTTP 200 + 业务码非 200），
 *   成功则返回 `data: null`。所以调用方要用 try/catch 而不是判断返回值
 *   （详见 `types/admin.ts` 里 `UpShelfCheckResult` 的说明）。
 */
export function checkBeforeUpShelf(id: string): Promise<null> {
  return http.get<null>(`/cs/courses/checkBeforeUpShelf/${id}`)
}

/** 上架（草稿同步到正式表，并发 MQ 通知搜索服务建索引） */
export function upShelf(id: string): Promise<void> {
  return http.post<void>('/cs/courses/upShelf', { id })
}

/** 下架（正式数据反向复制回草稿，并发 MQ） */
export function downShelf(id: string): Promise<void> {
  return http.post<void>('/cs/courses/downShelf', { id })
}

/** 删除课程（草稿与正式一起删） */
export function deleteCourse(id: string): Promise<void> {
  return http.delete<void>(`/cs/courses/delete/${id}`)
}

/** 分类树（一级分类，`tree=true` 带子级）—— 课程列表的筛选器在用 */
export function getCategoryTree(level = 1): Promise<CategoryVO[]> {
  return http.get<CategoryVO[]>('/cs/categorys/list', { params: { level, tree: true } })
}

// ==================== 建课五步（M7-12 第 2 期新增） ====================
/**
 * ★★ 顺序不能随意调 —— 照 `scripts/seed-demo-data.mjs` 的 `ensureCourse()`
 *   （已经跑通的参考实现）抄。硬约束只有一条，但很关键：
 *   **第 4 步（配题）必须排在上架之前** —— 建题 / 绑题都校验 `course_draft`，
 *   而上架会把草稿整份删掉 → 上架后再也改不了题。
 */

/**
 * 第 1 步：保存课程基本信息 → 返回 `{ id, name, step }`。
 *
 * ★ `id` 是后续所有步骤的 key：**新建时由这里产生**（不传 `id` 即新建）。
 * ★ 返回的 `step` 是后端记录的进度（1~5），前端据此知道"该从哪一步继续"。
 */
export function saveCourseBaseInfo(body: Record<string, unknown>): Promise<CourseSaveVO> {
  return http.post<CourseSaveVO>('/cs/courses/baseInfo/save', body)
}

/**
 * 课程名是否已存在（建课时防重）。
 *
 * ⚠️ **编辑态必须传 `id`** —— 后端签名是 `checkName(name, id)`，会用 `id` **排除自己**。
 *   不传的话，编辑一门"名字没改"的老课会被告知"名称已存在"，把向导卡死。
 */
export function checkCourseName(name: string, id?: string): Promise<NameExistVO> {
  return http.get<NameExistVO>('/cs/courses/checkName', { params: { name, id } })
}

/**
 * 第 2 / 4 步：保存训练目录（**全量覆盖**）。
 *
 * ⚠️ `step` 只接受 **2（目录）或 4（习题）**（`@Parameter` 原文："2-目录 4-习题"）——
 *   它的语义是"保存这一步的内容，并把进度推进到这一步"，不是一个自由进度值。
 */
export function saveCourseCatas(id: string, step: number, catas: CataSaveForm[]): Promise<void> {
  return http.post<void>(`/cs/courses/catas/save/${id}/${step}`, catas)
}

/**
 * 读回训练目录（树形）。
 *
 * ★ 第 3 步靠它拿 `cataId`：`media/save` 是按"训练动作节点 id"挂视频的，
 *   而 `cataId` 只有目录保存后才由后端生成。
 * ★ `see=true` 表示优先读已上架的（用户端用）；管理端**不传**（看草稿）。
 */
export function getCourseCatas(id: string, see?: boolean): Promise<CataVO[]> {
  return http.get<CataVO[]>(`/cs/courses/catas/${id}`, { params: { see } })
}

/** 第 3 步：保存训练动作信息（绑视频 + 组数/次数/休息/负重），全量覆盖 */
export function saveCourseMedia(id: string, list: CourseMediaForm[]): Promise<void> {
  return http.post<void>(`/cs/courses/media/save/${id}`, list)
}

/** 第 5 步：保存课程教练（★ **上架的硬门槛**：不走到这一步，`upShelf` 会被拒） */
export function saveCourseTeachers(body: CourseTeacherForm): Promise<void> {
  return http.post<void>('/cs/courses/teachers/save', body)
}

/** 读回课程教练（编辑时回显） */
export function getCourseTeachers(id: string, see?: boolean): Promise<CourseTeacherVO[]> {
  return http.get<CourseTeacherVO[]>(`/cs/courses/teachers/${id}`, { params: { see } })
}

/** 第 4 步：保存「节点 ↔ 题目」关联（全量覆盖） */
export function saveCourseSubjects(
  id: string,
  list: { cataId: string; subjectIds: string[] }[],
): Promise<void> {
  return http.post<void>(`/cs/courses/subjects/save/${id}`, list)
}

/**
 * 读回「节点 ↔ 题目」关联（编辑回显）。
 *
 * ★ `see` 的默认值是 **false**（只看草稿）—— 这与后端一致；
 *   要看已上架的得显式传 true。管理端编辑草稿时用默认值。
 */
export function getCourseSubjects(courseId: string, see?: boolean): Promise<CataSubjectVO[]> {
  return http.get<CataSubjectVO[]>(`/cs/courses/subjects/get/${courseId}`, { params: { see } })
}
