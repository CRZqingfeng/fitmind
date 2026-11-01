import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

import DefaultLayout from '@/layouts/DefaultLayout.vue'
import { getToken } from '@/utils/token'

declare module 'vue-router' {
  interface RouteMeta {
    /** 浏览器标签标题 */
    title?: string
    /** 免登录页面（默认全部需要登录） */
    public?: boolean
    /** 侧边菜单里是否显示 */
    hideInMenu?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true, hideInMenu: true },
  },
  // M7-12：注册页（★ 免登录；M7 方案 §7.1 第 1 页承诺过但此前未交付）
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { title: '注册', public: true, hideInMenu: true },
  },
  {
    path: '/',
    component: DefaultLayout,
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/views/HomeView.vue'),
        meta: { title: '首页' },
      },
      {
        path: 'courses',
        name: 'courses',
        component: () => import('@/views/CourseListView.vue'),
        meta: { title: '课程' },
      },
      {
        path: 'course/:id',
        name: 'course-detail',
        component: () => import('@/views/CourseDetailView.vue'),
        // ★ 详情页不在导航里高亮（hideInMenu 只影响菜单，这里靠 activeMenu 的匹配规则）
        meta: { title: '课程详情', hideInMenu: true },
      },
      // ==================== M7-3 交易链路 ====================
      {
        path: 'cart',
        name: 'cart',
        component: () => import('@/views/CartView.vue'),
        meta: { title: '购物车' },
      },
      {
        path: 'checkout',
        name: 'checkout',
        component: () => import('@/views/CheckoutView.vue'),
        // ★ 不在菜单里：结算页必须带着课程参数进入，从菜单直接点是打不开的
        meta: { title: '确认订单', hideInMenu: true },
      },
      {
        path: 'pay/:orderId',
        name: 'pay',
        component: () => import('@/views/PayView.vue'),
        meta: { title: '订单支付', hideInMenu: true },
      },
      {
        path: 'orders',
        name: 'orders',
        component: () => import('@/views/OrdersView.vue'),
        meta: { title: '我的订单' },
      },
      {
        path: 'orders/:id',
        name: 'order-detail',
        component: () => import('@/views/OrderDetailView.vue'),
        meta: { title: '订单详情', hideInMenu: true },
      },
      {
        path: 'refunds',
        name: 'refunds',
        component: () => import('@/views/RefundsView.vue'),
        meta: { title: '我的退款' },
      },
      // M7-12：我的优惠券 + 兑换码（★ M7 方案 §7.1 第 10 页承诺过，此前只有接口没有页面）
      {
        path: 'my-coupons',
        name: 'my-coupons',
        component: () => import('@/views/MyCouponsView.vue'),
        meta: { title: '我的优惠券', hideInMenu: true },
      },
      // ==================== M7-4 学习链路 ====================
      {
        path: 'my-courses',
        name: 'my-courses',
        component: () => import('@/views/MyCoursesView.vue'),
        meta: { title: '我的课程' },
      },
      {
        path: 'learn/:courseId',
        name: 'learn',
        component: () => import('@/views/LearnView.vue'),
        meta: { title: '训练', hideInMenu: true },
      },
      {
        path: 'plans',
        name: 'plans',
        component: () => import('@/views/PlansView.vue'),
        meta: { title: '训练计划', hideInMenu: true },
      },
      {
        path: 'growth',
        name: 'growth',
        component: () => import('@/views/GrowthView.vue'),
        meta: { title: '成长' },
      },
      // ==================== M11-2 计划模块（训练编排 / 执行）====================
      // ★★ 注意与上面那条 `plans`（`/plans` → PlansView）区分：
      //    那个是**课程学习计划**（某门课每周练几次），本模块是**训练编排**
      //    （周一练胸：深蹲 4 组 × 8 次 + 组休 90 秒）。路径 /plan ≠ /plans，
      //    一个字母之差，但语义、表、接口全不同，别互相跳转。
      {
        path: 'plan',
        name: 'plan',
        component: () => import('@/views/PlanHomeView.vue'),
        meta: { title: '计划' },
      },
      {
        path: 'plan/detail/:id',
        name: 'plan-detail',
        component: () => import('@/views/PlanDetailView.vue'),
        meta: { title: '计划详情', hideInMenu: true },
      },
      {
        path: 'plan/edit/:id',
        name: 'plan-edit',
        // `:id` 传 'new' 时由页面自己建一个新计划（见 PlanEditView 的注释）
        component: () => import('@/views/PlanEditView.vue'),
        meta: { title: '编排计划', hideInMenu: true },
      },
      {
        path: 'plan/exercise-lib',
        name: 'plan-exercise-lib',
        component: () => import('@/views/ExerciseLibView.vue'),
        meta: { title: '动作库', hideInMenu: true },
      },
      {
        path: 'plan/exercise/:id',
        name: 'plan-exercise',
        component: () => import('@/views/ExerciseDetailView.vue'),
        meta: { title: '动作详情', hideInMenu: true },
      },
      {
        path: 'plan/workout/:sessionId',
        name: 'plan-workout',
        component: () => import('@/views/WorkoutView.vue'),
        meta: { title: '训练中', hideInMenu: true },
      },
      // ==================== M11-3 饮食（P1）====================
      // ★ 三个页面都从「计划 → 饮食」tab 里进，不占菜单（菜单仍是 10 项）
      // ==================== M11-4 AI 定制（P2）====================
      // 结果页是一次性的（previewId 在内存里），刷新会重新生成一份预览
      {
        path: 'plan/ai',
        name: 'plan-ai',
        component: () => import('@/views/PlanAiView.vue'),
        meta: { title: 'AI 定制计划', hideInMenu: true },
      },
      {
        path: 'plan/food-lib',
        name: 'plan-food-lib',
        // 四个「记X餐」入口共用这一个页面，靠 query 的 mealType + date 区分
        component: () => import('@/views/FoodLibView.vue'),
        meta: { title: '饮食库', hideInMenu: true },
      },
      {
        path: 'plan/sport-lib',
        name: 'plan-sport-lib',
        component: () => import('@/views/SportLibView.vue'),
        meta: { title: '记运动', hideInMenu: true },
      },
      {
        path: 'plan/diet-plan/:id',
        name: 'plan-diet-plan',
        component: () => import('@/views/DietPlanDetailView.vue'),
        meta: { title: '饮食计划', hideInMenu: true },
      },
      // ==================== M7-6 体测评估 ====================
      {
        path: 'assessment',
        name: 'assessment',
        component: () => import('@/views/AssessmentView.vue'),
        meta: { title: '体测' },
      },
      // ==================== M7-10 动作考核 ====================
      {
        path: 'action-assess',
        name: 'action-assess',
        component: () => import('@/views/ActionAssessView.vue'),
        meta: { title: '动作考核' },
      },
      // ==================== M7-5 社区与消息 ====================
      {
        path: 'community',
        name: 'community',
        component: () => import('@/views/CommunityView.vue'),
        meta: { title: '社区' },
      },
      {
        path: 'community/question/:id',
        name: 'question-detail',
        component: () => import('@/views/QuestionDetailView.vue'),
        meta: { title: '问题详情', hideInMenu: true },
      },
      {
        path: 'messages',
        name: 'messages',
        component: () => import('@/views/MessagesView.vue'),
        // 入口在顶栏的消息图标（带未读角标），不在横向菜单里
        meta: { title: '消息中心', hideInMenu: true },
      },
      {
        path: 'ai',
        name: 'ai-coach',
        component: () => import('@/views/AiCoachView.vue'),
        meta: { title: 'AI 教练' },
      },
      {
        path: 'me',
        name: 'me',
        component: () => import('@/views/MeView.vue'),
        meta: { title: '我的' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在', public: true, hideInMenu: true },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

/**
 * 全局前置守卫
 *
 * 后端概念映射：路由守卫 ≈ 网关的 AccountAuthFilter —— 进页面前判断能不能进。
 * 区别：网关是"没 token 就 401"，守卫是"没 token 就送去登录页"（更友好）。
 *
 * ★ 这里只判断"有没有 token"，不校验 token 是否有效：
 *   有效性由后端说了算，前端校验等于把鉴权逻辑复制一份（两边不一致时更麻烦）。
 *   token 真的失效了，第一个请求会 401，由 request.ts 的续期逻辑兜底。
 */
router.beforeEach((to) => {
  const appName = import.meta.env.VITE_APP_NAME
  document.title = to.meta.title ? `${to.meta.title} · ${appName}` : appName

  if (to.meta.public) {
    // 已登录还去登录页 → 直接回首页（顺手挡掉手动敲 URL 的情况）
    if (to.name === 'login' && getToken()) {
      return { name: 'home' }
    }
    return true
  }

  if (!getToken()) {
    // 记住原本要去哪，登录后跳回去
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
