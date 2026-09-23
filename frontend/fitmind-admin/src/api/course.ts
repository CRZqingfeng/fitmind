/**
 * 课程管理（fm-course）
 *
 * ★ 路径前缀 `/cs`，走网关。
 *
 * ⚠️ **权限现状（实测，见 docs/待修复问题清单.md #A9）**：
 *   fm-course **没有开** `fm.auth.resource.admin.enabled` → 这些接口只校验"已登录"，
 *   **不校验管理员**。也就是说学员 token 也能调（实测通过）。
 *   前端仍然按"管理端能力"组织在 fitmind-admin 里 ——
 *   这里如实标注，是为了让看到代码的人知道**当前的安全边界在网关上不完整**，
 *   而不是误以为"放在 admin 工程里就安全了"。
 */
import type {
  CategoryVO,
  CoursePageQuery,
  CoursePageVO,
  PageDTO,
  SubjectVO,
} from '@/types/admin'
import { http } from '@/utils/request'
/** 课程分页（管理端） */
export function getCoursePage(query: CoursePageQuery): Promise<PageDTO<CoursePageVO>> {
  return http.get<PageDTO<CoursePageVO>>('/cs/courses/page', { params: query })
}

/** 课程基本信息（管理端；返回的是 `CourseBaseInfoVO`，比用户端多正文四段） */
export function getCourseBaseInfo(id: string): Promise<Record<string, unknown>> {
  return http.get<Record<string, unknown>>(`/cs/courses/baseInfo/${id}`)
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

/** 上架 */
export function upShelf(id: string): Promise<void> {
  return http.post<void>('/cs/courses/upShelf', { id })
}

/** 下架 */
export function downShelf(id: string): Promise<void> {
  return http.post<void>('/cs/courses/downShelf', { id })
}

/** 删除课程 */
export function deleteCourse(id: string): Promise<void> {
  return http.delete<void>(`/cs/courses/delete/${id}`)
}

/** 分类树（一级分类，`tree=true` 带子级） */
export function getCategoryTree(level = 1): Promise<CategoryVO[]> {
  return http.get<CategoryVO[]>('/cs/categorys/list', { params: { level, tree: true } })
}

/** 题目分页（管理端） */
export function getSubjectPage(query: {
  pageNo?: number
  pageSize?: number
  courseId?: string
  name?: string
}): Promise<PageDTO<SubjectVO>> {
  return http.get<PageDTO<SubjectVO>>('/cs/subjects/page', { params: query })
}

/** 某门课已绑定的题目（管理端） */
export function getCourseSubjects(courseId: string): Promise<Record<string, unknown>[]> {
  return http.get<Record<string, unknown>[]>(`/cs/courses/subjects/get/${courseId}`)
}
