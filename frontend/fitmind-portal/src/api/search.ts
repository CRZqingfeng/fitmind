import type { CourseSearchQuery, CourseSearchVO, PageDTO } from '@/types/course'
import { http } from '@/utils/request'

/**
 * 搜索与推荐接口（fm-search，网关前缀 /ss）
 *
 * ⚠️ 列表分页参数是 `pageNo` / `pageSize`（继承后端 `PageQuery`），不是 page/size。
 */

/** 猜你喜欢（后端按评分/报名热度算，size 默认 10） */
export function recommendBest(size = 8): Promise<CourseSearchVO[]> {
  return http.get<CourseSearchVO[]>('/ss/recommend/best', { params: { size } })
}

/** 免费专区 */
export function recommendFree(size = 8): Promise<CourseSearchVO[]> {
  return http.get<CourseSearchVO[]>('/ss/recommend/free', { params: { size } })
}

/** 最新上架 */
export function recommendNewest(size = 8): Promise<CourseSearchVO[]> {
  return http.get<CourseSearchVO[]>('/ss/recommend/new', { params: { size } })
}

/**
 * 课程检索 / 列表（用户端主入口）
 *
 * 同一个接口同时承担"列表浏览"和"关键词搜索"：
 * 不带 `keyword` 就是按筛选条件翻页，带上就走 ES 全文检索
 * （命中时返回的 `highlightName` 里有 `<em>`，渲染要过 `safeHighlight()`）。
 */
export function searchCourses(query: CourseSearchQuery): Promise<PageDTO<CourseSearchVO>> {
  return http.get<PageDTO<CourseSearchVO>>('/ss/courses/portal', { params: query })
}

/** 我的训练目标（一级分类 id 列表） */
export function getMyInterests(): Promise<string[]> {
  return http.get<string[]>('/ss/interests')
}

/** 保存我的训练目标 */
export function saveMyInterests(categoryIds: string[]): Promise<void> {
  return http.post<void>('/ss/interests', categoryIds)
}

/** 某个分类下的课程（用于"我的训练目标"点进去看） */
export function getCoursesOfCategory(categoryId: string, size = 10): Promise<CourseSearchVO[]> {
  return http.get<CourseSearchVO[]>(`/ss/interests/${categoryId}/courses`, { params: { size } })
}
