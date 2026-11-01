/**
 * 课程分类（fm-course，网关前缀 `/cs`）
 *
 * ★ 三级树：训练目标(1) → 训练部位(2) → 器械场景(3)。
 *
 * ⚠️ **两个反直觉点**（都在 `fm-course/src/main/resources/application.yml` 里核实过）：
 *
 *  ① **分类的读接口不是管理端专属**。`admin.pathPatterns` 只列了四个**写**操作
 *     （`POST:/categorys/add` · `PUT:/categorys/update` · `PUT:/categorys/disableOrEnable` ·
 *     `DELETE:/categorys/**`），`GET /categorys/**` **刻意不拦** ——
 *     源码注释原话："数据本就不敏感"，学员端也用它做筛选。
 *     → 所以验收脚本的负向断言**不能拿分类读接口当靶子**。
 *
 *  ② **同名分类在不同父下是不同记录**（"核心"可以同时挂在"减脂"和"塑形"下）——
 *     `seed-demo-data.mjs` 专门记过这条。所以树组件的 `node-key` 要能区分路径，
 *     校验与展示都不能只看 `name`。
 */
import type {
  CategoryAddForm,
  CategoryStatusForm,
  CategoryUpdateForm,
  CategoryVO,
} from '@/types/admin'
import { http } from '@/utils/request'

/**
 * 全部分类（树形）。
 *
 * ★ 与 `GET /categorys/list` 的区别：`list` 支持 `level/parentId/status/tree` 四个筛选参数，
 *   `all` 就是"整棵树，不给参数"。建课的分类选择器与分类管理页都要**全树**，
 *   所以用 `all`（也就不需要 `getAllOfOneLevel` —— 它和 `list?level=` 等价，
 *   接了就是多一个没人点的入口）。
 */
export function getAllCategories(): Promise<CategoryVO[]> {
  return http.get<CategoryVO[]>('/cs/categorys/all')
}

/** 分类详情（编辑时回填；`CategoryVO` 本身也含 children，这里只用它本体的字段） */
export function getCategory(id: string): Promise<CategoryVO> {
  return http.get<CategoryVO>(`/cs/categorys/${id}`)
}

/** 新增分类（`parentId` 传 `'0'` 表示一级分类） */
export function addCategory(body: CategoryAddForm): Promise<void> {
  return http.post<void>('/cs/categorys/add', body)
}

/**
 * 更新分类。
 *
 * ⚠️ 入参只有 `id / name / priority` —— **没有 `parentId`**。
 *   也就是说这个接口**不支持"换个父"**（`CategoryUpdateDTO` 里确实没有该字段）。
 *   前端因此不提供"移动分类"功能：与其做一个后端会忽略的控件，不如不做。
 */
export function updateCategory(body: CategoryUpdateForm): Promise<void> {
  return http.put<void>('/cs/categorys/update', body)
}

/** 启用 / 停用（1-启用 2-禁用） */
export function setCategoryStatus(body: CategoryStatusForm): Promise<void> {
  return http.put<void>('/cs/categorys/disableOrEnable', body)
}

/**
 * 删除分类。
 *
 * ⚠️ 后端会拒绝两种删除：**有子分类** 或 **已被课程使用**。
 *   所以前端**不做**"能不能删"的假预判，把按钮留着、让后端的报错说原因 ——
 *   用户看到的是权威理由，而不是前端猜出来的结论
 *   （注意 `courseNum` 只统计**直接挂在该分类下**的课程，拿它当判据会漏掉子分类）。
 */
export function deleteCategory(id: string): Promise<void> {
  return http.delete<void>(`/cs/categorys/${id}`)
}
