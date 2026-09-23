import type { CategoryVO, CataVO, CourseBaseInfoVO, CourseTeacherVO } from '@/types/course'
import { http } from '@/utils/request'

/**
 * 课程与分类接口（fm-course，网关前缀 /cs）
 */

/**
 * 分类树
 *
 * @param level  层级：1-训练目标 2-训练部位 3-器械场景（不传则返回全部）
 * @param status 状态：1-启用 2-禁用。★ 前端一律传 1 ——
 *               库里还有一批验收脚本造的垃圾分类，把 status 过滤当成第一道闸
 */
export function getCategoryList(
  params: { level?: number; parentId?: string; status?: number; tree?: boolean } = {},
): Promise<CategoryVO[]> {
  return http.get<CategoryVO[]>('/cs/categorys/list', {
    params: { status: 1, tree: true, ...params },
  })
}

/**
 * 课程基本信息
 *
 * ★ 用这个而不是 `/internal/{id}/full`：它是后者的**超集**，
 *   多带回课程正文四段（介绍/适合人群/详情/注意事项）与器械清单 —— 详情页正好都要。
 *   已实测用户端可正常调用（HTTP 200）。
 *
 * ⚠️ 但它是管理端语义的接口：**未上架的课也会返回**（`fromDraft=true` / `status!=2`）。
 *   调用方必须自己判断，不能直接把结果当"可展示的课程"用。
 */
export function getCourseBaseInfo(id: string): Promise<CourseBaseInfoVO> {
  return http.get<CourseBaseInfoVO>(`/cs/courses/baseInfo/${id}`)
}

/**
 * 训练目录（用户端专用，只返回已上架的内容）
 *
 * ★ 与 `/cs/courses/catas/{id}` 的区别：那个多一个 `see` 参数且默认 false
 *   （管理端视角，会带回未上架的草稿节点）。用户端用这个更安全。
 */
export function getCourseCatalogues(id: string): Promise<CataVO[]> {
  return http.get<CataVO[]>(`/cs/courses/${id}/catalogs`)
}

/** 课程教练（`see=true` 表示"优先看已上架的"，即用户端视角） */
export function getCourseTeachers(id: string): Promise<CourseTeacherVO[]> {
  return http.get<CourseTeacherVO[]>(`/cs/courses/teachers/${id}`, { params: { see: true } })
}
