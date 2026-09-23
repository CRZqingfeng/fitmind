#!/usr/bin/env node
/**
 * ============================================================
 * FitMind 演示数据 seed（M7-2 的前置）
 * ------------------------------------------------------------
 * ★ 为什么需要这个脚本：
 *   M6-7 全量回归结束时把 fm_course.course 清空了（0 行），
 *   ES 索引 fitmind_course 也是 0 条文档；而 category 表里留下
 *   23 条验收脚本造的垃圾分类（`M4-3分类788962` 之类）。
 *   → 前端 M7-2（首页推荐 / 课程列表 / 课程详情）没有数据可展示，
 *     "页面条数 == 接口 total" 这类验收断言会退化成 0 == 0，等于没测。
 *
 * ★ 为什么走**真实接口**而不是直接写 SQL：
 *   1. 建课是一条有状态机的流程（step 1→2→3→5），直写 SQL 极容易漏表或
 *      造出"接口查不出来"的脏数据（course / course_draft / course_catalogue /
 *      course_catalogue_draft / course_teacher 都要一致）
 *   2. 上架会通过 MQ 触发 fm-search 建 ES 索引 —— 直接写库不会触发，
 *      搜索接口就查不到（这正是"数据在库里但搜不到"的典型原因）
 *   3. 这套流程本身就是 M7-8 管理端建课页要用的，脚本顺带把它跑通了
 *
 * 用法：
 *   node scripts/seed-demo-data.mjs              # 幂等补齐（已存在的课程跳过）
 *   node scripts/seed-demo-data.mjs --clean      # 顺带禁用垃圾分类
 *   node scripts/seed-demo-data.mjs --stats      # 顺带给课程刷上 sold/score（模拟历史数据）
 *   node scripts/seed-demo-data.mjs --clean --stats
 *
 * 前置：fm-course(8084) / fm-search(8085) / fm-user(8082) / fm-media(8083) /
 *       fm-auth(8081) / fm-gateway(10010) 已启动，ES 可用。
 * ============================================================
 */

import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const GATEWAY = 'http://localhost:10010'
/**
 * 学员账号：用来走**学员流程**（报名、学习、下单、看课）。
 *
 * ★ 曾经这里写的是「建课接口只要求"已登录"，不限角色」——
 *   那句话描述的其实是 `docs/待修复问题清单.md` 里的 **#A9 越权漏洞**，
 *   而 M7-11 已把它修掉：fm-course 的管理端接口（建课/改课/上架/下架/删课/题目）
 *   现在要求**管理员**（`UserType.ADMIN == 1`）。
 *   本脚本的建课调用因此改走下面的 `call()` 包装层，自动换成管理员 token。
 */
const STUDENT_PHONE = '13800010001'
/**
 * 教练账号（`UserType.COACH == 3`）。
 *
 * ★ 注意：教练**不是**管理员 —— `AdminAuthInterceptor` 只认 `ADMIN(1)`，
 *   实测教练 token 调 fm-course 管理端接口一样是 403（见清单 #A13）。
 *   这个账号在脚本里的用途是"另一个用户"（造多条数据、验证越权边界），
 *   而**不是**用来做管理端操作的。
 */
const COACH_PHONE = '13800010002'
/** M1 种子数据里所有账号的统一密码 */
const SEED_PASSWORD = '123456'
/** M1 种子数据里的「测试教练」 */
const COACH_USER_ID = 2

const args = process.argv.slice(2)
const OPT_CLEAN = args.includes('--clean')
const OPT_STATS = args.includes('--stats')
/** 清空既有课程数据后重建（★ 只清"本次 seed 名单内"的课程，见 resetCourses 的断言） */
const OPT_RESET = args.includes('--reset')

/**
 * ★★ 未知选项要**报错退出**，不能静默忽略。
 *
 * 这条是踩出来的：`--reset` 曾经在代码里**不存在**（一次改动被中断、之后没复查），
 * 但 README 一直写着"支持 --reset" —— 于是 `node seed-demo-data.mjs --reset`
 * 会**老老实实跑完、什么也不清**，看起来"成功"，实际那个参数只被 includes() 读了一次就丢了。
 * ★ 和项目里"yml 里配了 ≠ 依赖在"是同一类问题：**配置/参数没生效，而且不报错**。
 */
const KNOWN_OPTIONS = ['--clean', '--stats', '--reset']
const unknownOptions = args.filter((a) => !KNOWN_OPTIONS.includes(a))
if (unknownOptions.length) {
  console.error(`❌ 未知选项：${unknownOptions.join(', ')}（可用：${KNOWN_OPTIONS.join(' / ')}）`)
  process.exit(2)
}

// ============================================================
// 数据定义
// ============================================================

/** 分类树：一级（训练目标）→ 二级（训练部位）→ 三级（器械场景） */
const CATEGORY_TREE = [
  {
    name: '增肌', children: [
      { name: '胸部', children: [{ name: '哑铃' }, { name: '徒手' }] },
      { name: '背部', children: [{ name: '哑铃' }, { name: '弹力带' }, { name: '杠铃' }] },
      { name: '腿部', children: [{ name: '徒手' }, { name: '杠铃' }] },
    ],
  },
  {
    name: '减脂', children: [
      { name: '全身', children: [{ name: '徒手' }, { name: '跳绳' }] },
      { name: '核心', children: [{ name: '徒手' }] },
    ],
  },
  {
    name: '塑形', children: [
      { name: '核心', children: [{ name: '徒手' }] },
      { name: '手臂', children: [{ name: '哑铃' }] },
    ],
  },
  {
    name: '保持', children: [
      { name: '营养', children: [{ name: '无器械' }] },
    ],
  },
]

/**
 * 课程定义
 *
 * 字段对应 CourseBaseInfoSaveDTO：
 *   courseType 1-训练计划课 2-单动作教学 3-理论饮食课
 *   goalType   1-增肌 2-减脂 3-塑形 4-保持
 *   trainingLevel 1-零基础 2-初级 3-中级 4-高级
 *   free       1-免费 2-付费（注意是数字，不是布尔）
 */
const COURSES = [
  {
    name: '零基础哑铃增肌 12 周计划',
    cat: ['增肌', '胸部', '哑铃'],
    courseType: 1, free: 2, price: 19900,
    goalType: 1, trainingLevel: 1, totalDays: 84, weeklyFrequency: 3,
    equipment: '哑铃,卧推凳', estimatedCalories: 320,
    validDuration: 365,
    courseIntroduce: '专为完全没有训练经验的增肌新手设计的 12 周系统计划。',
    usePeople: 'BMI 偏低、想增加肌肉量但不知从何练起的男性新手。',
    courseDetail: '每周 3 练，采用全身分化（Day A 下肢主导 / Day B 上肢推 / Day C 上肢拉），'
      + '第 4、8 周设减量周避免过度训练。每个动作都配标准示范视频与目标肌群说明。',
    precautions: '训练前充分热身 5-10 分钟；如有关节不适请立即停止并咨询医生。',
    requiredEquipment: '哑铃（10-25kg 可调）、卧推凳',
    days: [
      { name: 'Day 1 下肢主导', actions: ['高脚杯深蹲', '哑铃罗马尼亚硬拉', '哑铃弓步蹲', '站姿提踵'] },
      { name: 'Day 2 上肢推', actions: ['哑铃卧推', '哑铃肩上推举', '哑铃飞鸟', '窄距俯卧撑'] },
      { name: 'Day 3 上肢拉', actions: ['单臂哑铃划船', '哑铃弯举', '俯身反向飞鸟'] },
    ],
  },
  {
    name: '哑铃胸肌塑形训练',
    cat: ['增肌', '胸部', '哑铃'],
    courseType: 1, free: 1, price: 0,
    goalType: 1, trainingLevel: 2, totalDays: 28, weeklyFrequency: 3,
    equipment: '哑铃,卧推凳', estimatedCalories: 280,
    validDuration: 180,
    courseIntroduce: '四周集中强化胸大肌上部与中缝的免费训练计划。',
    usePeople: '已有 3 个月以上训练基础、想让胸型更饱满的训练者。',
    courseDetail: '以哑铃为主，强调离心控制与顶峰收缩；每周递增负荷，第四周为减量周。',
    precautions: '卧推时注意肩胛后缩下沉，避免肩关节代偿。',
    requiredEquipment: '哑铃、可调卧推凳',
    days: [
      { name: 'Day 1 上胸优先', actions: ['上斜哑铃卧推', '上斜哑铃飞鸟', '哑铃仰卧上拉'] },
      { name: 'Day 2 中缝与下胸', actions: ['平板哑铃卧推', '哑铃飞鸟', '双杠臂屈伸'] },
    ],
  },
  {
    name: '弹力带背部训练（居家版）',
    cat: ['增肌', '背部', '弹力带'],
    courseType: 1, free: 2, price: 9900,
    goalType: 1, trainingLevel: 2, totalDays: 21, weeklyFrequency: 4,
    equipment: '弹力带', estimatedCalories: 240,
    validDuration: 180,
    courseIntroduce: '不用去健身房，一条弹力带练出背部线条。',
    usePeople: '居家训练、器械有限但想改善圆肩驼背的人群。',
    courseDetail: '围绕"伸展-收缩"两个方向设计动作，重点激活背阔肌与菱形肌。',
    precautions: '使用前检查弹力带是否老化开裂。',
    requiredEquipment: '阻力 15-30 磅弹力带',
    days: [
      { name: 'Day 1 水平拉', actions: ['弹力带坐姿划船', '弹力带面拉', '弹力带直臂下压'] },
      { name: 'Day 2 垂直拉', actions: ['弹力带高位下拉', '弹力带单臂划船'] },
    ],
  },
  {
    name: '居家徒手燃脂 HIIT',
    cat: ['减脂', '全身', '徒手'],
    courseType: 1, free: 1, price: 0,
    goalType: 2, trainingLevel: 2, totalDays: 14, weeklyFrequency: 5,
    equipment: '徒手', estimatedCalories: 400,
    validDuration: 120,
    courseIntroduce: '每天 20 分钟、零器械的高强度间歇燃脂课。',
    usePeople: '时间紧张、想在家快速消耗热量的上班族。',
    courseDetail: '每个动作 40 秒全力 + 20 秒休息，4 个动作为一轮，共 4 轮。',
    precautions: 'HIIT 强度较高，请量力而行；膝踝关节不适者慎做跳跃类动作。',
    requiredEquipment: '无需器械，需约 2㎡ 空间',
    days: [
      { name: 'Day 1 下肢爆发', actions: ['开合跳', '深蹲跳', '箭步蹲交替跳', '登山跑'] },
      { name: 'Day 2 核心与心肺', actions: ['波比跳', '平板支撑', '俄罗斯转体', '高抬腿'] },
    ],
  },
  {
    name: '跳绳减脂 4 周速成',
    cat: ['减脂', '全身', '跳绳'],
    courseType: 1, free: 2, price: 6900,
    goalType: 2, trainingLevel: 1, totalDays: 28, weeklyFrequency: 5,
    equipment: '跳绳', estimatedCalories: 380,
    validDuration: 180,
    courseIntroduce: '从"跳不过 10 个"到连续跳 10 分钟的 4 周进阶方案。',
    usePeople: '完全不会跳绳、想低成本刷脂的人群。',
    courseDetail: '四周难度递增：第 1 周 30 秒×8 组，第 4 周 3 分钟×5 组，穿插小腿放松训练。',
    precautions: '务必穿有缓冲的鞋并在木地板/塑胶地面进行，水泥地易伤膝踝。',
    requiredEquipment: '跳绳、缓冲运动鞋',
    days: [
      { name: 'Day 1 基础节奏', actions: ['单摇慢跳', '开合跳绳', '跳绳后小腿拉伸'] },
      { name: 'Day 2 耐力间歇', actions: ['30 秒冲刺跳', '交叉步跳', '单脚交替跳'] },
    ],
  },
  {
    name: '办公族核心康复训练',
    cat: ['塑形', '核心', '徒手'],
    courseType: 1, free: 1, price: 0,
    goalType: 3, trainingLevel: 1, totalDays: 21, weeklyFrequency: 4,
    equipment: '徒手,瑜伽垫', estimatedCalories: 180,
    validDuration: 180,
    courseIntroduce: '针对久坐人群的下背疼痛与核心无力问题设计。',
    usePeople: '每天坐 8 小时以上、腰背酸痛、体态前倾的办公族。',
    courseDetail: '以低强度激活为主，训练腹横肌与多裂肌，配合胸椎活动度训练。',
    precautions: '腰椎间盘突出急性期请勿训练，先就医。',
    requiredEquipment: '瑜伽垫',
    days: [
      { name: 'Day 1 深层核心激活', actions: ['死虫式', '鸟狗式', '仰卧骨盆后倾'] },
      { name: 'Day 2 体态纠正', actions: ['胸椎旋转', '臀桥', '靠墙天使'] },
    ],
  },
  {
    name: '哑铃手臂线条塑造',
    cat: ['塑形', '手臂', '哑铃'],
    courseType: 1, free: 2, price: 12900,
    goalType: 3, trainingLevel: 3, totalDays: 35, weeklyFrequency: 3,
    equipment: '哑铃', estimatedCalories: 220,
    validDuration: 180,
    courseIntroduce: '让手臂告别"拜拜肉"，练出清晰的肱二头与肱三头分离度。',
    usePeople: '有一定训练基础、追求手臂线条的女性或男性训练者。',
    courseDetail: '二头与三头分开训练日，强调孤立发力与慢速离心。',
    precautions: '肘关节不适时减少负重，避免借力摆动。',
    requiredEquipment: '哑铃（3-10kg）',
    days: [
      { name: 'Day 1 肱二头', actions: ['哑铃交替弯举', '锤式弯举', '集中弯举'] },
      { name: 'Day 2 肱三头', actions: ['哑铃颈后臂屈伸', '俯身臂屈伸', '窄距俯卧撑'] },
    ],
  },
  {
    name: '杠铃硬拉进阶训练',
    cat: ['增肌', '背部', '杠铃'],
    courseType: 1, free: 2, price: 25900,
    goalType: 1, trainingLevel: 4, totalDays: 42, weeklyFrequency: 2,
    equipment: '杠铃,杠铃片', estimatedCalories: 350,
    validDuration: 365,
    courseIntroduce: '面向进阶者的硬拉技术打磨与力量提升计划。',
    usePeople: '硬拉 1RM 已达体重 1.2 倍以上、想突破瓶颈的训练者。',
    courseDetail: '每周 2 次：一次大重量低次数（3-5RM），一次技术组（60% 1RM 做 5×5）。',
    precautions: '大重量训练务必在保护下进行；腰背不适立即停止。',
    requiredEquipment: '杠铃、杠铃片、举重腰带',
    days: [
      { name: 'Day 1 力量日', actions: ['传统硬拉', '罗马尼亚硬拉', '杠铃耸肩'] },
      { name: 'Day 2 技术日', actions: ['相扑硬拉', '直腿硬拉'] },
    ],
  },
  {
    name: '增肌饮食与营养入门（理论）',
    cat: ['保持', '营养', '无器械'],
    courseType: 3, free: 1, price: 0,
    goalType: 4, trainingLevel: 1, totalDays: 1, weeklyFrequency: 1,
    equipment: '无', estimatedCalories: 0,
    validDuration: 365,
    courseIntroduce: '不练也能看：增肌期该吃多少、怎么吃、怎么算。',
    usePeople: '训练很勤但体重不涨的"吃不够"人群。',
    courseDetail: '讲解热量盈余、蛋白质配比、碳水时机与补剂选择，附一周食谱模板。',
    precautions: '本课程为理论内容，不替代专业营养师建议。',
    requiredEquipment: '无',
    days: [
      { name: '第 1 讲 热量与三大营养素', actions: ['热量盈余怎么算', '蛋白质该吃多少', '碳水的时机'] },
    ],
  },
  {
    name: '单动作精讲：标准深蹲',
    cat: ['增肌', '腿部', '徒手'],
    courseType: 2, free: 1, price: 0,
    goalType: 1, trainingLevel: 1, totalDays: 1, weeklyFrequency: 1,
    equipment: '徒手', estimatedCalories: 60,
    validDuration: 365,
    courseIntroduce: '一个视频讲透深蹲：从脚位到呼吸，逐帧纠错。',
    usePeople: '深蹲时膝盖内扣、起身弯腰、找不到腿部发力感的训练者。',
    courseDetail: '分解为站距、核心、下蹲深度、起身轨迹四个环节逐一讲解常见错误与纠正方法。',
    precautions: '膝关节有伤者请先咨询康复医生。',
    requiredEquipment: '无',
    days: [
      { name: '动作精讲', actions: ['深蹲的脚位与站距', '下蹲深度的判断', '起身发力与呼吸'] },
    ],
  },
  {
    // ★★ 这门课是给「动作小测」演示用的（M7-6），免费 → 学员可直接报名
    //    `quiz: true` 表示"建课流程里要插一步配题"（必须在**上架之前**，见 ensureCourseQuiz）
    name: '动作小测演示课（含测试题）',
    cat: ['增肌', '腿部', '徒手'],
    courseType: 1, free: 1, price: 0,
    goalType: 1, trainingLevel: 1, totalDays: 1, weeklyFrequency: 2,
    equipment: '哑铃', estimatedCalories: 120,
    validDuration: 365,
    courseIntroduce: '开练前先用几道小测把动作要点过一遍，练的时候心里有数。',
    usePeople: '想在上手前自检动作要点的训练者。',
    courseDetail: '课程的第一个动作配了 3 道小测题（单选 / 判断 / 多选），答对按题目分值累计积分。',
    precautions: '小测用于自检，不能替代教练的现场指导。',
    requiredEquipment: '哑铃',
    quiz: true,
    days: [
      { name: '下肢基础', actions: ['高脚杯深蹲', '哑铃罗马尼亚硬拉'] },
    ],
  },
]

/** 动作参数模板（按动作名匹配，匹配不到的用默认值） */
const ACTION_PARAMS = {
  高脚杯深蹲:       { sets: 4, reps: 12, restSeconds: 90,  weight: 16,   actionType: 1, equipment: '哑铃',   targetMuscles: '股四头肌,臀大肌' },
  哑铃罗马尼亚硬拉: { sets: 4, reps: 10, restSeconds: 90,  weight: 20,   actionType: 1, equipment: '哑铃',   targetMuscles: '腘绳肌,臀大肌' },
  哑铃弓步蹲:       { sets: 3, reps: 12, restSeconds: 60,  weight: 12,   actionType: 1, equipment: '哑铃',   targetMuscles: '股四头肌,臀大肌' },
  站姿提踵:         { sets: 4, reps: 15, restSeconds: 45,  weight: 20,   actionType: 1, equipment: '哑铃',   targetMuscles: '小腿三头肌' },
  哑铃卧推:         { sets: 4, reps: 10, restSeconds: 90,  weight: 18,   actionType: 1, equipment: '哑铃',   targetMuscles: '胸大肌,肱三头肌' },
  上斜哑铃卧推:     { sets: 4, reps: 10, restSeconds: 90,  weight: 16,   actionType: 1, equipment: '哑铃',   targetMuscles: '胸大肌上部' },
  平板哑铃卧推:     { sets: 4, reps: 10, restSeconds: 90,  weight: 18,   actionType: 1, equipment: '哑铃',   targetMuscles: '胸大肌' },
  哑铃肩上推举:     { sets: 3, reps: 12, restSeconds: 75,  weight: 12,   actionType: 1, equipment: '哑铃',   targetMuscles: '三角肌前束' },
  哑铃飞鸟:         { sets: 3, reps: 12, restSeconds: 60,  weight: 10,   actionType: 1, equipment: '哑铃',   targetMuscles: '胸大肌' },
  上斜哑铃飞鸟:     { sets: 3, reps: 12, restSeconds: 60,  weight: 9,    actionType: 1, equipment: '哑铃',   targetMuscles: '胸大肌上部' },
  窄距俯卧撑:       { sets: 3, reps: 12, restSeconds: 60,  weight: 0,    actionType: 1, equipment: '徒手',   targetMuscles: '肱三头肌,胸大肌' },
  双杠臂屈伸:       { sets: 3, reps: 10, restSeconds: 75,  weight: 0,    actionType: 1, equipment: '徒手',   targetMuscles: '肱三头肌,胸大肌下部' },
  哑铃仰卧上拉:     { sets: 3, reps: 12, restSeconds: 60,  weight: 14,   actionType: 1, equipment: '哑铃',   targetMuscles: '胸大肌,背阔肌' },
  单臂哑铃划船:     { sets: 4, reps: 10, restSeconds: 75,  weight: 20,   actionType: 1, equipment: '哑铃',   targetMuscles: '背阔肌' },
  哑铃弯举:         { sets: 3, reps: 12, restSeconds: 60,  weight: 10,   actionType: 1, equipment: '哑铃',   targetMuscles: '肱二头肌' },
  俯身反向飞鸟:     { sets: 3, reps: 15, restSeconds: 60,  weight: 6,    actionType: 1, equipment: '哑铃',   targetMuscles: '三角肌后束' },
  弹力带坐姿划船:   { sets: 4, reps: 15, restSeconds: 60,  weight: 0,    actionType: 1, equipment: '弹力带', targetMuscles: '背阔肌,菱形肌' },
  弹力带面拉:       { sets: 3, reps: 15, restSeconds: 60,  weight: 0,    actionType: 1, equipment: '弹力带', targetMuscles: '三角肌后束' },
  弹力带直臂下压:   { sets: 3, reps: 15, restSeconds: 60,  weight: 0,    actionType: 1, equipment: '弹力带', targetMuscles: '背阔肌' },
  弹力带高位下拉:   { sets: 4, reps: 12, restSeconds: 60,  weight: 0,    actionType: 1, equipment: '弹力带', targetMuscles: '背阔肌' },
  弹力带单臂划船:   { sets: 3, reps: 12, restSeconds: 60,  weight: 0,    actionType: 1, equipment: '弹力带', targetMuscles: '背阔肌' },
  开合跳:           { sets: 4, reps: 40, restSeconds: 20,  weight: 0,    actionType: 2, equipment: '徒手',   targetMuscles: '全身' },
  深蹲跳:           { sets: 4, reps: 15, restSeconds: 45,  weight: 0,    actionType: 2, equipment: '徒手',   targetMuscles: '股四头肌,臀大肌' },
  箭步蹲交替跳:     { sets: 4, reps: 20, restSeconds: 45,  weight: 0,    actionType: 2, equipment: '徒手',   targetMuscles: '股四头肌' },
  登山跑:           { sets: 4, reps: 40, restSeconds: 30,  weight: 0,    actionType: 2, equipment: '徒手',   targetMuscles: '核心,肩部' },
  波比跳:           { sets: 4, reps: 12, restSeconds: 45,  weight: 0,    actionType: 2, equipment: '徒手',   targetMuscles: '全身' },
  平板支撑:         { durationSeconds: 60, sets: 3, restSeconds: 45, weight: 0, actionType: 1, equipment: '徒手', targetMuscles: '腹横肌' },
  俄罗斯转体:       { sets: 3, reps: 20, restSeconds: 45,  weight: 5,    actionType: 1, equipment: '哑铃',   targetMuscles: '腹斜肌' },
  高抬腿:           { sets: 4, reps: 40, restSeconds: 30,  weight: 0,    actionType: 2, equipment: '徒手',   targetMuscles: '髂腰肌' },
  单摇慢跳:         { durationSeconds: 60, sets: 8, restSeconds: 30, weight: 0, actionType: 2, equipment: '跳绳', targetMuscles: '小腿三头肌' },
  开合跳绳:         { durationSeconds: 60, sets: 6, restSeconds: 30, weight: 0, actionType: 2, equipment: '跳绳', targetMuscles: '全身' },
  跳绳后小腿拉伸:   { durationSeconds: 90, sets: 2, restSeconds: 20, weight: 0, actionType: 3, equipment: '跳绳', targetMuscles: '小腿三头肌' },
  '30 秒冲刺跳':    { durationSeconds: 30, sets: 8, restSeconds: 30, weight: 0, actionType: 2, equipment: '跳绳', targetMuscles: '全身' },
  交叉步跳:         { durationSeconds: 45, sets: 6, restSeconds: 30, weight: 0, actionType: 2, equipment: '跳绳', targetMuscles: '全身' },
  单脚交替跳:       { durationSeconds: 45, sets: 6, restSeconds: 30, weight: 0, actionType: 2, equipment: '跳绳', targetMuscles: '小腿三头肌' },
  死虫式:           { sets: 3, reps: 12, restSeconds: 45,  weight: 0,    actionType: 1, equipment: '瑜伽垫', targetMuscles: '腹横肌' },
  鸟狗式:           { sets: 3, reps: 12, restSeconds: 45,  weight: 0,    actionType: 1, equipment: '瑜伽垫', targetMuscles: '核心,竖脊肌' },
  仰卧骨盆后倾:     { sets: 3, reps: 15, restSeconds: 45,  weight: 0,    actionType: 1, equipment: '瑜伽垫', targetMuscles: '腹横肌' },
  胸椎旋转:         { sets: 3, reps: 10, restSeconds: 30,  weight: 0,    actionType: 3, equipment: '瑜伽垫', targetMuscles: '胸椎' },
  臀桥:             { sets: 3, reps: 15, restSeconds: 45,  weight: 0,    actionType: 1, equipment: '瑜伽垫', targetMuscles: '臀大肌' },
  靠墙天使:         { sets: 3, reps: 12, restSeconds: 30,  weight: 0,    actionType: 3, equipment: '徒手',   targetMuscles: '肩袖肌群' },
  哑铃交替弯举:     { sets: 4, reps: 12, restSeconds: 60,  weight: 10,   actionType: 1, equipment: '哑铃',   targetMuscles: '肱二头肌' },
  锤式弯举:         { sets: 3, reps: 12, restSeconds: 60,  weight: 10,   actionType: 1, equipment: '哑铃',   targetMuscles: '肱桡肌' },
  集中弯举:         { sets: 3, reps: 12, restSeconds: 45,  weight: 8,    actionType: 1, equipment: '哑铃',   targetMuscles: '肱二头肌' },
  哑铃颈后臂屈伸:   { sets: 3, reps: 12, restSeconds: 60,  weight: 10,   actionType: 1, equipment: '哑铃',   targetMuscles: '肱三头肌' },
  俯身臂屈伸:       { sets: 3, reps: 12, restSeconds: 60,  weight: 6,    actionType: 1, equipment: '哑铃',   targetMuscles: '肱三头肌' },
  传统硬拉:         { sets: 5, reps: 5,  restSeconds: 150, weight: 80,   actionType: 1, equipment: '杠铃',   targetMuscles: '竖脊肌,臀大肌,腘绳肌' },
  罗马尼亚硬拉:     { sets: 4, reps: 8,  restSeconds: 120, weight: 60,   actionType: 1, equipment: '杠铃',   targetMuscles: '腘绳肌,臀大肌' },
  杠铃耸肩:         { sets: 4, reps: 12, restSeconds: 75,  weight: 50,   actionType: 1, equipment: '杠铃',   targetMuscles: '斜方肌' },
  相扑硬拉:         { sets: 4, reps: 6,  restSeconds: 150, weight: 70,   actionType: 1, equipment: '杠铃',   targetMuscles: '臀大肌,内收肌' },
  直腿硬拉:         { sets: 4, reps: 8,  restSeconds: 120, weight: 50,   actionType: 1, equipment: '杠铃',   targetMuscles: '腘绳肌' },
  '第 1 讲 热量与三大营养素': { sets: 1, reps: 1, restSeconds: 0, weight: 0, actionType: 3, equipment: '无', targetMuscles: '-' },
}

const DEFAULT_PARAMS = { sets: 3, reps: 12, restSeconds: 60, weight: 0, actionType: 1, equipment: '徒手', targetMuscles: '全身' }

// ============================================================
// 基础工具
// ============================================================

let passed = 0
let failed = 0
const failures = []

/**
 * ★ `check` 才做断言；`ok` 是「无条件记录成功」的简写。
 *
 * 第一版把两者混成一个函数（`ok(name, detail)`），结果 verify() 里按
 * `ok(name, condition, detail)` 调用时，**condition 被当成 detail 直接打印了** ——
 * 输出看着是"✅ 全部通过"，其实 total=0 也照样通过。
 * 这类"断言写错导致假绿"是本项目最忌讳的失败（见验收脚本清单第 1 条）。
 */
function check(name, condition, detail = '') {
  if (condition) {
    passed++
    console.log(`  ✅ ${name}${detail ? `（${detail}）` : ''}`)
  } else {
    failed++
    failures.push(name + (detail ? ` —— ${detail}` : ''))
    console.log(`  ❌ ${name}${detail ? ` —— ${detail}` : ''}`)
  }
}

/** 无条件成功（用于"这一步执行完了"这类没有判定条件的记录） */
function ok(name, detail = '') {
  check(name, true, detail)
}

function section(title) {
  console.log(`\n【${title}】`)
}

/** ==========================================================================
 * ★★ 管理端调用自动改用「管理员」token —— #A9 收尾（2026-09-21）
 *
 * 背景：**管理端接口**（建课/改课/上下架/删课/题目/建券/发券/媒资列表与删除/评分…）
 *       现在都要求**管理员**（UserType=1）。以前它们只要求「已登录」，所以本脚本一直用
 *       **学员** token 调它们 —— 那个越权漏洞就是 docs/待修复问题清单.md 里的 #A9。
 *
 * ★★ 为什么是**管理员**、而不是教练：`UserType` 是 ADMIN(1) / STUDENT(2) / COACH(3)，
 *      而 SDK 的 AdminAuthInterceptor 只认 ADMIN —— **实测教练 token 一样会被 403**。
 *      这与 `/ts/admin`、`/es/admin` 等其余管理端接口一致（它们同样只认管理员）。
 *      （"教练建题"在本项目里从来不是一条**权限**，只是 #A9 无校验时的副作用。）
 *
 * 做法：原实现改名 `callRaw`，`call` 变成一层薄包装 ——
 *       **路径命中管理端清单时**，把调用方的 token 换成管理员 token。
 *
 * ★ 两个刻意的边界：
 *   ① 调用方**没带 token**（如「未登录应 401」的用例）**不替换** ——
 *      否则会把 401 断言测成 200，变成假绿。
 *   ② 管理员 token **惰性获取**：各脚本里 staff 登录的位置不一致（有的要到文件后半段
 *      才登录），惰性获取让这层包装对**所有**脚本完全一致，也不必挪动原有代码。
 *
 * ★★ 清单必须覆盖**所有服务**，不只 fm-course：#A9 是分两次收口的 ——
 *    M7-9 给 fm-media / fm-promotion / fm-exam 开了鉴权，但**没有同步改脚本**，
 *    于是 accept-m2-1（媒资列表/删除）与 accept-m4-3（建券/发券）从那天起一直在 403，
 *    直到 M7-11 跑全量回归才暴露。所以这里**一次性列全**。
 * ========================================================================== */
let _adminToken = null
/**
 * 管理端路径清单 —— 与各服务 `application.yml` 的 `fm.auth.resource.admin.pathPatterns` 对齐：
 *   · fm-trade / fm-data / fm-aigc / fm-message / fm-learning / fm-exam → 默认 `/admin/**`
 *   · fm-media     → `/medias`（含 GET 列表与 DELETE）、`/medias/{id}/preview-url`、`DELETE /medias/{id}`
 *                    ★ `/files/**` **刻意不圈**：它是通用文件服务（bizType 含 avatar 等学员用途）
 *   · fm-promotion → `/coupons/**`、`/exchange-codes/**`
 *   · fm-course    → 见下面逐条（用 `METHOD:/path`：同前缀里混着学员端读接口）
 */
function isAdminPath(method, path) {
  // 去掉网关前缀（/cs、/ms、/prs、/es…），还原成服务内部看到的路径
  const p = String(path || '').split('?')[0]
    .replace(/^\/(as|us|ms|cs|ss|ls|rs|ts|ps|prs|sms|es|ds|ais)(?=\/)/, '');
  const m = String(method || 'GET').toUpperCase();
  // ① 通用：所有服务的 /admin/** 都是管理端
  if (p === '/admin' || p.startsWith('/admin/')) return true;
  // ② fm-media（★ 刻意不圈：upload-signature / */confirm / **/files/** —— 学员也在用）
  if (p === '/medias') return true;
  if (p.startsWith('/medias/') && p.endsWith('/preview-url')) return true;
  if (m === 'DELETE' && p.startsWith('/medias/')) return true;
  // ③ fm-promotion（★ /user-coupons/** 与 /internal/** 是学员端在用，不在其中）
  if (p === '/coupons' || p.startsWith('/coupons/')) return true;
  if (p.startsWith('/exchange-codes/')) return true;
  // ④ fm-course
  if (p === '/courses/baseInfo/save') return m === 'POST';
  if (p.startsWith('/courses/catas/save/')) return m === 'POST';
  if (p.startsWith('/courses/media/save/')) return m === 'POST';
  if (p === '/courses/teachers/save') return m === 'POST';
  if (p === '/courses/upShelf' || p === '/courses/downShelf') return m === 'POST';
  if (p.startsWith('/courses/delete/')) return m === 'DELETE';
  if (p === '/courses/page' || p === '/courses/checkName') return m === 'GET';
  if (p.startsWith('/courses/checkBeforeUpShelf/')) return m === 'GET';
  if (p.startsWith('/courses/catas/')) return m === 'GET';
  if (p.startsWith('/subjects')) return true;
  if (p.startsWith('/courses/subjects/')) return true;
  if (p === '/categorys/add') return m === 'POST';
  if (p === '/categorys/update' || p === '/categorys/disableOrEnable') return m === 'PUT';
  return p.startsWith('/categorys/') && m === 'DELETE';
}
async function call(method, path, opts = {}) {
  // ★ `noAdminSwap: true` 用于**故意用非管理员 token 探测 403** 的负向用例 ——
  //   不加这个开关就会把"应 403"换成管理员 token 测成 200，
  //   把安全断言变成永远失败（实测回归里踩到过）。
  if (opts && opts.token && !opts.noAdminSwap && isAdminPath(method, path)) {
    if (!_adminToken) {
      const r = await callRaw('POST', '/as/accounts/admin/login', {
        body: { type: 1, cellPhone: '13800010003', password: '123456', rememberMe: false },
      });
      _adminToken = r.json?.data;
      if (!_adminToken) throw new Error('管理员登录失败（管理端调用需要 UserType=1）：' + JSON.stringify(r.json));
    }
    opts = { ...opts, token: _adminToken };
  }
  return callRaw(method, path, opts);
}
async function callRaw(method, path, { token, body } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = token
  const res = await fetch(GATEWAY + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  let json = null
  try {
    json = await res.json()
  } catch {
    /* 非 JSON 响应（如 204） */
  }
  return { status: res.status, json }
}

async function login() {
  const codeRes = await call('POST', `/us/codes?phone=${STUDENT_PHONE}`)
  const code = codeRes.json?.data
  if (!code) throw new Error(`获取验证码失败：${JSON.stringify(codeRes.json)}`)
  const loginRes = await call('POST', '/as/accounts/login', {
    body: { type: 2, cellPhone: STUDENT_PHONE, password: code, rememberMe: true },
  })
  const token = loginRes.json?.data
  if (!token) throw new Error(`登录失败：${JSON.stringify(loginRes.json)}`)
  return token
}

/**
 * 教练登录（建题等管理端能力需要 staff 角色）
 *
 * ★ 两个容易混的点：
 *   ① 走的是 `/as/accounts/admin/login` 而不是 `/as/accounts/login` ——
 *      后者带 `isStaff=false`，会拒绝非学员账号（教练登进去会报"角色不符"）。
 *   ② 请求体里的 `type` 是**登录方式**（1-密码 / 2-验证码），不是用户类型。
 *      用密码登录还能顺带绕开"验证码 60 秒/手机号"的频控，脚本连跑两次不会卡住。
 */
async function loginStaff(phone) {
  const res = await call('POST', '/as/accounts/admin/login', {
    body: { type: 1, cellPhone: phone, password: SEED_PASSWORD, rememberMe: false },
  })
  const token = res.json?.data
  if (!token) throw new Error(`教练登录失败：${JSON.stringify(res.json)}`)
  return token
}

/**
 * 按扩展名推断 Content-Type
 *
 * ★ 存在的理由（不是"顺手写的工具函数"）：直传 MinIO 时不带 Content-Type，
 *   对象会被存成 `binary/octet-stream`，**浏览器就不会把它当图片渲染**。
 *   见 `uploadAsset` 里那段注释与 `scripts/fix-minio-content-type.py`。
 */
function guessContentType(filename) {
  const ext = String(filename).toLowerCase().split('.').pop()
  const map = {
    svg: 'image/svg+xml',
    png: 'image/png',
    jpg: 'image/jpeg',
    jpeg: 'image/jpeg',
    webp: 'image/webp',
    gif: 'image/gif',
    mp4: 'video/mp4',
    webm: 'video/webm',
  }
  // 兜底给二进制流（比"猜错类型"更安全：至少不会被当成文本渲染）
  return map[ext] ?? 'application/octet-stream'
}

/**
 * 上传一个小文件到 fm-media（走"申请签名 → PUT 直传 MinIO → confirm"三步）
 *
 * 用途：给课程做封面图。★ 这里不校验扩展名（后端 applyUploadSignature 也不校验），
 * 所以直接传一张合法 SVG 即可。
 */
async function uploadAsset(token, filename, bytes, duration = 0) {
  const sig = await call('POST',
    `/ms/medias/upload-signature?filename=${encodeURIComponent(filename)}&size=${bytes.length}`,
    { token })
  const mediaId = sig.json?.data?.mediaId
  const uploadUrl = sig.json?.data?.uploadUrl
  if (!mediaId || !uploadUrl) return { error: `申请签名失败：${sig.json?.msg || sig.status}` }

  /**
   * ★★ 直传时必须带上正确的 `Content-Type`，否则对象会被存成
   * `binary/octet-stream`，**浏览器不会把它当图片渲染** ——
   * `<img src>` 直接触发 onerror，前端只能显示降级色块。
   *
   * 这个坑很隐蔽：对象本身读得到（直链 HTTP 200），所以排查时容易一路往
   * "桶权限"上想（我们确实先怀疑了桶），其实是 MIME 类型不对。
   * 实测：修之前 41 个 .svg + 10 个 .png 全是 octet-stream，
   * 修完封面才真正显示出来。存量对象的修复脚本见 `scripts/fix-minio-content-type.py`。
   *
   * ★ 预签名 URL 的签名不覆盖 Content-Type（已验证），所以这里加头不会导致签名不匹配。
   */
  const contentType = guessContentType(filename)
  const put = await fetch(uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': contentType },
    body: bytes,
  }).catch((e) => ({ status: -1, text: () => Promise.resolve(String(e)) }))
  if (put?.status !== 200) {
    let detail = ''
    try { detail = (await put.text()).slice(0, 160) } catch { /* 拿不到响应体就算了 */ }
    return { error: `PUT 直传失败 HTTP ${put?.status}：${detail}` }
  }

  const confirm = await call('POST', `/ms/medias/${mediaId}/confirm`,
    { token, body: { duration } })
  if (confirm.json?.code !== 200) {
    return { error: `confirm 失败：${confirm.json?.msg || confirm.status}` }
  }

  // ★★ 这里踩过一个坑：confirm 返回的 MediaDTO **没有 mediaUrl 字段**
  //    （实际字段只有 id / filename / fileId / duration / size / coverUrl / status）。
  //    第一版按 `d.mediaUrl || d.url` 取值，两边都是 undefined →
  //    10 张封面全是"上传成功但拿不到地址"，而调用方又把返回值吞了，
  //    表面上只看到"0/10 张"，完全不知道卡在哪一步。
  //    → 用 fileId 拼 MinIO 直链；桶名从预签名 URL 的 path 里解析，不硬编码。
  const d = confirm.json?.data || {}
  const fileId = d.fileId || ''
  let url = null
  if (fileId) {
    const u = new URL(uploadUrl)
    const bucket = u.pathname.split('/').filter(Boolean)[0]
    url = `${u.origin}/${bucket}/${fileId}`
  }
  return { mediaId, url, fileId }
}

/**
 * 生成一张渐变 SVG 封面
 *
 * ★ 为什么用 SVG 而不是手写 PNG：
 *   手写 PNG 要自己算 chunk 长度和 CRC32，稍偏一点就是"能上传、但不合法"的坏图 ——
 *   第一版正是这么写的，结果 10 张封面**全部上传失败**，而且因为忽略 stderr 完全没报错。
 *   SVG 是纯文本，生成不出非法结构，渐变效果也比纯色好看。
 */
function makeCoverSvg(index, title) {
  const hue = (index * 37 + 15) % 360
  const hue2 = (hue + 45) % 360
  const short = xmlEscape(String(title).slice(0, 12))
  const font = "system-ui,-apple-system,'Microsoft YaHei',sans-serif"
  return Buffer.from(
    `<svg xmlns="http://www.w3.org/2000/svg" width="480" height="300" viewBox="0 0 480 300">`
    + `<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">`
    + `<stop offset="0%" stop-color="hsl(${hue},72%,58%)"/>`
    + `<stop offset="100%" stop-color="hsl(${hue2},78%,36%)"/>`
    + `</linearGradient></defs>`
    + `<rect width="480" height="300" fill="url(#g)"/>`
    + `<text x="34" y="170" font-size="29" font-weight="bold" fill="#ffffff" `
    + `font-family="${font}">${short}</text>`
    + `<text x="34" y="208" font-size="15" fill="rgba(255,255,255,.78)" `
    + `font-family="${font}">FitMind</text>`
    + `</svg>`,
    'utf-8',
  )
}

function xmlEscape(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/**
 * 执行一条 mysql 语句
 *
 * ★ Windows 上必须写成 `mysql.exe`：`execFileSync('mysql', ...)` 不做 PATHEXT 补全，
 *   会直接 ENOENT —— 而如果同时把 stderr 丢掉，症状就是"SQL 静默不生效"（第一版就是这样）。
 */
function mysql(sql) {
  try {
    return execFileSync('mysql.exe', [
      '-h127.0.0.1', '-uroot', '-p1234', '-N', '--default-character-set=utf8mb4',
      '-e', sql,
    ], { encoding: 'utf-8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
  } catch (e) {
    // ★ 必须把 mysql 自己的 stderr 带出来：execFileSync 的 message 只显示命令行，
    //   真正的错误（SQL 语法问题、多行参数被截断）全在 stderr 里。
    //   丢掉 stderr 的症状就是"SQL 静默不生效"——第一版正是这样。
    // ★ 取「非 warning 的最后一个非空行」：
    //   mysql 会把 `[Warning] Using a password...` 写进 stderr 的**第一行**，
    //   只取第一行就会把它当成错误显示，真正的 `ERROR 1054 ...` 反而被盖住 ——
    //   本次就是这样多绕了一圈才发现是"列名不存在"。
    const lines = e && e.stderr
      ? String(e.stderr).split(String.fromCharCode(10)).map((l) => l.trim()).filter(Boolean)
      : []
    const real = lines.filter((l) => !l.includes('Warning')).pop()
    const err = real || lines[0] || ''
    console.log(`     ⚠️ mysql 执行失败：${err || String(e.message).split(String.fromCharCode(10))[0]}`)
    return ''
  }
}

// ============================================================
// 步骤实现
// ============================================================

/**
 * ⓪ 清空既有课程数据（--reset）
 *
 * ★ 两阶段：**先断言"库里没有不属于本次 seed 名单的课程"**，通过才开始删。
 *   如果库里存在手工建的课，直接清空就等于把别人的东西删了 ——
 *   "删/改/迁"这类操作必须先校验再执行（本项目的一条固定规矩，M7-1 误删过东西）。
 *
 * ★ 为什么要删这么多表：一门课的"内容"分散在课程/目录/动作媒资/教练/题目/题目绑定
 *   的**草稿表与正式表**里，少删一张就会在重建时撞唯一约束或留下孤儿行。
 */
async function resetCourses() {
  section('⓪ 清空既有课程数据（--reset）')

  const inList = COURSES.map((c) => `'${c.name.replace(/'/g, "''")}'`).join(',')
  const foreign = Number(
    mysql(`SELECT COUNT(*) FROM fm_course.course WHERE name NOT IN (${inList});`) || '0',
  )
  if (foreign > 0) {
    check('库里没有"非本次 seed"的课程', false,
      `发现 ${foreign} 门外部课程 —— 已中止清理，未删除任何数据`)
    return false
  }
  const before = mysql('SELECT COUNT(*) FROM fm_course.course;') || '0'

  for (const t of [
    'course_cata_subject', 'course_cata_subject_draft',
    'course_catalogue', 'course_catalogue_draft',
    'course_teacher', 'course_teacher_draft',
    'course_content', 'course_content_draft',
    'course_draft', 'course', 'subject',
  ]) {
    mysql(`DELETE FROM fm_course.${t};`)
  }
  const after = mysql('SELECT COUNT(*) FROM fm_course.course;') || '?'
  check('课程相关表已清空', after === '0', `原有 ${before} 门 → 现在 ${after} 门`)

  const es = await fetch('http://localhost:9201/fitmind_course/_delete_by_query?refresh=true', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query: { match_all: {} } }),
  }).then((r) => r.json()).catch(() => null)
  check('ES 索引已清空（否则搜索会返回已删课程）', !!es, `删除 ${es?.deleted ?? '?'} 条文档`)

  return true
}

/** ① 禁用验收脚本遗留的垃圾分类（★ 用"禁用"而不是"删除" —— 可逆） */
async function disableGarbageCategories(token) {
  section('① 禁用垃圾分类（`M4-3分类xxx` 这类验收脚本产物）')
  const res = await call('GET', '/cs/categorys/list?tree=false', { token })
  const all = res.json?.data || []
  const garbage = all.filter((c) => /^M\d/.test(c.name || '') && /分类|课程|测试/.test(c.name || ''))
  ok('识别出垃圾分类', `${garbage.length} 条（共 ${all.length} 条分类）`)

  let disabled = 0
  for (const c of garbage) {
    if (c.status === 2) continue // 已禁用
    const r = await call('PUT', '/cs/categorys/disableOrEnable',
      { token, body: { id: c.id, status: 2 } })
    if (r.json?.code === 200) disabled++
  }
  ok('已禁用', `${disabled} 条（原本已禁用的跳过）`)
  console.log(`     → 数据仍在库里，需要恢复把 status 改回 1 即可（可逆，没有删除任何数据）`)
}

/** ② 建分类树（幂等：按名字查，不存在才建） */
async function ensureCategories(token) {
  section('② 建立分类树（一级 训练目标 → 二级 训练部位 → 三级 器械场景）')

  const list = await call('GET', '/cs/categorys/list?tree=true', { token })
  const existing = list.json?.data || []
  const idOf = new Map()
  // ★ key 必须用「全路径」：同名分类在不同父下是不同记录
  //   （如「核心」同时挂在「减脂」和「塑形」下，「徒手」更是出现在 5 个二级下）
  for (const c1 of existing) {
    idOf.set(c1.name, c1.id)
    for (const c2 of c1.children || []) {
      idOf.set(`${c1.name}/${c2.name}`, c2.id)
      for (const c3 of c2.children || []) {
        idOf.set(`${c1.name}/${c2.name}/${c3.name}`, c3.id)
      }
    }
  }

  let created = 0
  for (const lv1 of CATEGORY_TREE) {
    if (!idOf.has(lv1.name)) {
      await call('POST', '/cs/categorys/add', { token, body: { name: lv1.name, parentId: 0 } })
      const again = await call('GET', '/cs/categorys/list?level=1&tree=false', { token })
      const found = (again.json?.data || []).find((c) => c.name === lv1.name)
      if (found) {
        idOf.set(lv1.name, found.id)
        created++
      }
    }
    const lv1Id = idOf.get(lv1.name)
    for (const lv2 of lv1.children || []) {
      const lv2Key = `${lv1.name}/${lv2.name}`
      if (!idOf.has(lv2Key)) {
        await call('POST', '/cs/categorys/add', { token, body: { name: lv2.name, parentId: lv1Id } })
        const again = await call('GET', `/cs/categorys/list?level=2&parentId=${lv1Id}&tree=false`, { token })
        const found = (again.json?.data || []).find((c) => c.name === lv2.name)
        if (found) {
          idOf.set(lv2Key, found.id)
          created++
        }
      }
      const lv2Id = idOf.get(lv2Key)
      for (const lv3 of lv2.children || []) {
        const key = `${lv1.name}/${lv2.name}/${lv3.name}`
        if (!idOf.has(key)) {
          await call('POST', '/cs/categorys/add', { token, body: { name: lv3.name, parentId: lv2Id } })
          const again = await call('GET', `/cs/categorys/list?level=3&parentId=${lv2Id}&tree=false`, { token })
          const found = (again.json?.data || []).find((c) => c.name === lv3.name)
          if (found) {
            idOf.set(key, found.id)
            created++
          }
        }
      }
    }
  }
  ok('分类树就绪', `新建 ${created} 个，复用 ${idOf.size - created} 个`)
  return idOf
}

/**
 * ③ 上传动作视频 + 封面图
 *
 * @param needCoverNames 需要封面的课程名。★ 只给"待新建"的课程上传 ——
 *   否则每重复跑一次 seed 就往 MinIO 堆 10 张再也用不上的图（幂等指的是结果一致，不是重复劳动）。
 */
async function uploadAssets(token, needCoverNames) {
  section('③ 上传媒资（动作视频复用 + 每门课封面图）')

  // 动作视频：只传 4 个通用视频，多门课复用（避免 30+ 次上传拖慢 seed）
  const videoNames = ['哑铃标准动作示范', '徒手动作示范', '弹力带动作示范', '跳绳动作示范']
  const videos = []
  for (const name of videoNames) {
    const bytes = Buffer.from(`FitMindFakeVideo-${name}-${Date.now()}`)
    const r = await uploadAsset(token, `${name}.mp4`, bytes, 180)
    if (r?.mediaId) videos.push(r.mediaId)
  }
  ok('动作视频已上传', `${videos.length}/${videoNames.length} 个（多门课复用，避免重复上传）`)

  // 封面图：只给待创建（或上次缺封面）的课程上传
  const covers = new Map()
  let coverOk = 0
  let firstError = ''
  for (let i = 0; i < COURSES.length; i++) {
    const c = COURSES[i]
    if (!needCoverNames.includes(c.name)) continue
    const svg = makeCoverSvg(i, c.name)
    const r = await uploadAsset(token, `cover-${i + 1}.svg`, svg, 0)
    if (r?.url) {
      covers.set(c.name, r.url)
      coverOk++
    } else if (!firstError) {
      // 只记第一条失败原因就够（同时失败时原因必然相同）
      firstError = r?.error || '未知（既没返回 url 也没返回 error）'
    }
  }
  if (firstError) console.log(`     ⚠️ 封面失败原因：${firstError}`)
  check('课程封面已上传', coverOk === needCoverNames.length,
    `${coverOk}/${needCoverNames.length} 张（本次需要封面的课程数）`)
  console.log('     ⚠️ MinIO 桶未开匿名读 → 封面直链会 403；前端已做 onerror 降级（见 CourseCard.vue）')

  return { videos, covers }
}

/** ④ 建一门课（幂等） */
async function ensureCourse(token, spec, ctx) {
  const { idOf, videos, covers } = ctx

  // 幂等（第一道）：用搜索接口按名字找已存在的课程
  // ⚠️ 不用 `/cs/courses/page?name=` —— 实测它**没有按 name 过滤**（返回的是全量列表且拿不到匹配），
  //    结果幂等失效：十门课全走到"创建"，再被唯一约束拒掉，全报"名称已存在"，
  //    看起来像"seed 失败了"，其实数据是好好的。
  const exist = await call('GET',
    `/ss/courses/portal?pageNo=1&pageSize=50&keyword=${encodeURIComponent(spec.name)}`, { token })
  const hit = (exist.json?.data?.list || []).find((c) => c.name === spec.name)
  if (hit) return { skipped: true, id: hit.id }

  const [lv1, lv2, lv3] = spec.cat
  const body = {
    name: spec.name,
    courseType: spec.courseType,
    coverUrl: covers.get(spec.name) || undefined,
    firstCateId: idOf.get(lv1),
    secondCateId: idOf.get(`${lv1}/${lv2}`),
    thirdCateId: idOf.get(`${lv1}/${lv2}/${lv3}`),
    free: spec.free,
    price: spec.price,
    validDuration: spec.validDuration,
    goalType: spec.goalType,
    trainingLevel: spec.trainingLevel,
    totalDays: spec.totalDays,
    weeklyFrequency: spec.weeklyFrequency,
    equipment: spec.equipment,
    estimatedCalories: spec.estimatedCalories,
    courseIntroduce: spec.courseIntroduce,
    usePeople: spec.usePeople,
    courseDetail: spec.courseDetail,
    precautions: spec.precautions,
    requiredEquipment: spec.requiredEquipment,
  }
  const created = await call('POST', '/cs/courses/baseInfo/save', { token, body })
  const courseId = created.json?.data?.id
  if (!courseId) {
    const msg = created.json?.msg || '建课失败'
    // 幂等（第二道）：即使预查没命中，只要后端说"名称已存在"，就说明这门课已经在库里 ——
    // 那是"已完成"，不是"失败"。没有这一道的话，重复跑 seed 会报一片红。
    if (msg.includes('已存在')) return { skipped: true }
    return { error: msg }
  }

  // step 2：训练目录
  const cataPayload = spec.days.map((d, i) => ({
    type: 1,
    name: d.name,
    index: i + 1,
    sections: d.actions.map((a) => ({ type: 2, name: a })),
  }))
  const cataRes = await call('POST', `/cs/courses/catas/save/${courseId}/2`,
    { token, body: cataPayload })
  if (cataRes.json?.code !== 200) {
    return { error: `保存目录失败：${cataRes.json?.msg}` }
  }

  // step 3：动作绑视频 + 动作参数
  const tree = await call('GET', `/cs/courses/catas/${courseId}`, { token })
  const days = tree.json?.data || []
  const mediaPayload = []
  let vi = 0
  for (const day of days) {
    for (const act of day.sections || []) {
      const params = ACTION_PARAMS[act.name] || DEFAULT_PARAMS
      const mediaId = videos.length ? videos[vi++ % videos.length] : undefined
      mediaPayload.push({
        cataId: act.id,
        mediaId,
        videoName: mediaId ? `${act.name}.mp4` : undefined,
        mediaDuration: params.durationSeconds || 120,
        trailer: vi <= 2, // 前两个动作可试看
        ...params,
      })
    }
  }
  const mediaRes = await call('POST', `/cs/courses/media/save/${courseId}`,
    { token, body: mediaPayload })
  if (mediaRes.json?.code !== 200) {
    return { error: `保存动作信息失败：${mediaRes.json?.msg}` }
  }

  // step 5：教练
  const teacherRes = await call('POST', '/cs/courses/teachers/save',
    { token, body: { id: courseId, teachers: [{ id: COACH_USER_ID, isShow: true }] } })
  if (teacherRes.json?.code !== 200) {
    return { error: `绑定教练失败：${teacherRes.json?.msg}` }
  }

  // step 6：动作小测（★ 必须在「上架」之前 —— 建题与绑定都只对**草稿态**课程开放，
  //   而上架会把草稿删掉；详见 ensureCourseQuiz 的注释）
  if (spec.quiz) {
    const quiz = await ensureCourseQuiz(ctx.coachToken, courseId, days)
    if (quiz.error) return { error: `配题失败：${quiz.error}` }
    console.log(`     └ 已配 ${quiz.count} 道小测题 → 动作「${quiz.cataName}」`)
  }

  // 上架（会触发 MQ → fm-search 建索引）
  const up = await call('POST', '/cs/courses/upShelf', { token, body: { id: courseId } })
  if (up.json?.code !== 200) {
    return { error: `上架失败：${up.json?.msg}` }
  }

  return { id: courseId }
}

/**
 * 动作小测：题目模板（★ M7-6）
 *
 * 三种题型各一道，正好覆盖前端的三种渲染分支：
 *   1-单选（radio）· 2-多选（checkbox）· 4-判断（正确/错误）
 * （3-不定向在交互上与多选一致，演示数据里就不重复了。）
 *
 * `answer` 用**从 1 开始的选项序号**，多选用逗号分隔 —— 判断题 1-正确 2-错误。
 */
const QUIZ_TEMPLATES = [
  {
    suffix: '发力前的准备姿势，最关键的一点是？',
    subjectType: 1,
    difficulty: 1,
    options: ['先收紧核心、稳定呼吸再开始', '快速甩起借力', '憋住气一口气做到底'],
    answer: '1',
    analysis: '准备姿势决定发力效率：核心收紧 + 呼吸节奏稳定，动作才可控，也最不容易受伤。',
    score: 5,
  },
  {
    suffix: '训练中出现尖锐疼痛，应该立刻停下并调整动作。',
    subjectType: 4,
    difficulty: 1,
    options: null,
    answer: '1',
    analysis: '判断题：正确。「酸胀」可以继续观察，但「尖锐疼痛」是停止信号，硬撑是受伤的主要原因。',
    score: 5,
  },
  {
    suffix: '下列哪些做法有助于提升这个动作的训练效果？',
    subjectType: 2,
    difficulty: 2,
    options: [
      '控制下放速度（离心阶段）',
      '每组都做到完全力竭',
      '记录重量与次数以便渐进超负荷',
      '组间休息时保持静态拉伸',
    ],
    answer: '1,3',
    analysis:
      '离心控制与渐进超负荷是增肌的核心变量；每组都力竭会显著抬高疲劳成本；组间静态拉伸反而会短暂降低力量输出。',
    score: 10,
  },
]

/**
 * 给**草稿态**课程配好动作小测（建题 + 绑定到训练动作）★ M7-6
 *
 * ============================================================
 * ★★ 为什么必须在"上架之前"做（这条是实测撞出来的）
 * ============================================================
 * `POST /cs/subjects`（建题）与 `POST /cs/courses/subjects/save/{courseId}`（绑定）
 * **都要求课程处于草稿态**：
 *   · 建题：`saveSubject` 里 `courseDraftService.getById(courseId) == null` → 报
 *     "课程不存在，请先保存课程基本信息"（而课程在正式表里明明是存在的）
 *   · 绑定：同样校验 `course_draft`，且节点要存在于 `course_catalogue_draft`
 *
 * 而**上架时会把草稿整份删掉**（实测 course 544：上架后 `course_draft` 与
 * `course_catalogue_draft` 都是 0 行）。
 * → 所以**已经上架的课程再也加不了题**。这不是 bug，是原设计的取舍：
 *   题目属于"课程内容"，跟着课程走完整的"草稿 → 上架"流程，上架时一起同步到正式表。
 *   第一版 seed 想给已存在的课程补题，连报 60 次"课程不存在"，就是这么来的。
 *
 * 所以这里做成"造一门**带小测的演示课**"：走完整建课流程（含配题）再上架。
 * 这也是唯一能同时验证"上架时题目一起同步"的姿势。
 */
async function ensureCourseQuiz(coachToken, courseId, draftTree) {
  const actions = (draftTree || []).flatMap((d) => d.sections || [])
  if (!actions.length) return { error: '草稿目录为空，无法配题' }
  // 只给第一个动作配题：既够演示，也能让"其他动作没有题"成为一种可见的对照
  const target = actions[0]

  const subjectIds = []
  for (const t of QUIZ_TEMPLATES) {
    const res = await call('POST', '/cs/subjects', {
      token: coachToken,
      body: {
        courseId,
        name: `${target.name}｜${t.suffix}`,
        subjectType: t.subjectType,
        difficulty: t.difficulty,
        options: t.options || undefined,
        answer: t.answer,
        analysis: t.analysis,
        score: t.score,
      },
    })
    const sid = res.json?.data
    if (!sid) return { error: `建题失败：${res.json?.msg}` }
    subjectIds.push(sid)
  }

  // 绑定（全量覆盖语义：传这套 = 该课程只挂这些题）
  const bind = await call('POST', `/cs/courses/subjects/save/${courseId}`, {
    token: coachToken,
    body: [{ cataId: target.id, subjectIds }],
  })
  if (bind.json?.code !== 200) return { error: `绑定题目失败：${bind.json?.msg}` }

  return { cataId: target.id, cataName: target.name, count: subjectIds.length }
}

/** ⑤ 演示用：给课程刷上 score / sold（模拟历史热度） */
async function applyStats() {
  section('⑤ 模拟历史数据（score 评分 / sold 报名人数）')
  console.log('     ★ 说明：这两个字段的"真实来源"分别是评价流程与下单流程，seed 没有走那两条链路。')
  console.log('       为了让演示页面不出现"全 0 人报名"，这里直接改库造历史数据。')
  // ★ SQL 必须是**单行**：Windows 下多行字符串传给 mysql 的 -e 会被截断，
  //   症状是"命令跑了但库没变"（第一版报错信息里 `-e ` 后面是空的，就是截断的证据）
  // ⚠️ 第一版这里写了 `SET sold = ...` 但当时 course 表还没有这一列，
  //   报 `ERROR 1054 Unknown column 'sold'` —— 而 mysql 的 `[Warning] Using a password...`
  //   恰好占满 stderr 第一行，把真错误盖住了，看起来像"命令没执行"。
  mysql('UPDATE fm_course.course SET score = 40 + (id * 7) % 10, sold = 30 + (id * 17) % 470 WHERE status = 2;')

  const stat = mysql('SELECT COUNT(*), MIN(sold), MAX(sold), MIN(score), MAX(score) FROM fm_course.course WHERE status=2;')
  check('score / sold 已写入', !!stat, `课程数/sold范围/score范围 = ${stat.replace(/\t/g, ' / ')}`)

  // ★★ 改完库**必须**让 fm-search 重建索引，否则就是"库里有、页面上没有"：
  //    /ss/courses/portal 读的是 ES，而 ES 只在这几种情况下刷新 ——
  //    上架 / 下架 / 过期，以及 M7-3 新增的 course.update。
  //    （平时 sold 的变动由 fm-course 消费 order.pay 后发 course.update，这里手工补上这一环。）
  const ids = mysql('SELECT id FROM fm_course.course WHERE status = 2;').split(/\r?\n/).filter(Boolean)
  let routed = 0
  for (const id of ids) {
    if (await publishViaMgmtApi('course.topic', 'course.update', id)) routed++
  }
  check('索引刷新消息已投递', routed === ids.length,
    `${routed}/${ids.length} 条（course.topic → course.update）`)
}

/**
 * 通过 RabbitMQ 管理 API 发一条消息。
 *
 * <p>★ 为什么不用 AMQP 客户端（amqplib）：只为了"改完库刷一次索引"就多加一个
 * 运行时依赖（还有连不上时的重试、连接泄漏等一堆事），不划算。
 * 管理 API 是 RabbitMQ 自带能力，一个 POST 就够，且<b>返回的 routed 字段
 * 会告诉我们消息有没有真的进队列</b> —— 比 AMQP 的默认发送更可观测
 * （exchange 存在但没有队列绑定它时，AMQP 是静默丢弃的）。</p>
 */
async function publishViaMgmtApi(exchange, routingKey, payload) {
  try {
    const env = readDotEnv()
    const auth = Buffer.from(`${env.RABBITMQ_USER}:${env.RABBITMQ_PASSWORD}`).toString('base64')
    const port = env.RABBITMQ_CONSOLE_PORT || '15672'
    const res = await fetch(`http://localhost:${port}/api/exchanges/%2F/${exchange}/publish`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Basic ${auth}` },
      body: JSON.stringify({
        properties: {},
        routing_key: routingKey,
        payload: String(payload),
        payload_encoding: 'string',
      }),
    })
    if (!res.ok) {
      console.log(`     ⚠️ 管理 API 返回 HTTP ${res.status}（凭据取自 docker/.env）`)
      return false
    }
    return Boolean((await res.json()).routed)
  } catch (e) {
    console.log(`     ⚠️ 发消息失败：${String(e.message).split(String.fromCharCode(10))[0]}`)
    return false
  }
}

/** 读取 docker/.env（不打印任何值，避免把凭据写进日志） */
function readDotEnv() {
  // 用脚本自身位置推导，这样从任何 cwd 运行都对
  const envPath = fileURLToPath(new URL('../docker/.env', import.meta.url))
  const env = {}
  for (const line of readFileSync(envPath, 'utf-8').split(/\r?\n/)) {
    const s = line.trim()
    if (!s || s.startsWith('#') || !s.includes('=')) continue
    const i = s.indexOf('=')
    env[s.slice(0, i).trim()] = s.slice(i + 1).trim().replace(/^["']|["']$/g, '')
  }
  return env
}

/** ⑥ 验证：数据是否真的能被搜索/推荐查到 */
async function verify(token) {
  section('⑥ 验证（★ 不只看数据库，要看"接口能不能查到"）')

  const page = await call('GET', '/ss/courses/portal?pageNo=1&pageSize=100', { token })
  const total = page.json?.data?.total ?? -1
  check('搜索列表 /ss/courses/portal 能查到课程', total > 0, `total=${total}`)

  for (const [path, label] of [
    ['/ss/recommend/best?size=6', '猜你喜欢'],
    ['/ss/recommend/free?size=6', '免费专区'],
    ['/ss/recommend/new?size=6', '最新上架'],
  ]) {
    const r = await call('GET', path, { token })
    const n = (r.json?.data || []).length
    check(`${label} ${path.split('?')[0]} 有数据`, n > 0, `${n} 条`)
  }

  const kw = await call('GET', '/ss/courses/portal?pageNo=1&pageSize=10&keyword=%E5%93%91%E9%93%83', { token })
  const kwTotal = kw.json?.data?.total ?? 0
  check('关键词搜索「哑铃」有命中', kwTotal > 0, `total=${kwTotal}`)
  const hl = (kw.json?.data?.list || []).find((c) => c.highlightName)
  check('★ 返回了高亮字段 highlightName（前端要安全渲染）', !!hl,
    hl ? `例：${hl.highlightName}` : '（没有高亮字段）')

  const cate = await call('GET', '/cs/categorys/list?level=1&tree=true', { token })
  const enabled = (cate.json?.data || []).filter((c) => c.status === 1)
  check('一级分类里已启用的是"真实"分类', enabled.length > 0 && enabled.length <= 8,
    `启用 ${enabled.length} 条：${enabled.map((c) => c.name).join('/')}`)

  // ★ M7-6：动作小测 —— 题目能不能被**学员**拉到（这才是前端真正依赖的路径）
  const quiz = (page.json?.data?.list || []).find((c) => c.name.startsWith('动作小测演示课'))
  if (!quiz) {
    check('小测演示课已上架', false, '搜索结果里没找到')
    return
  }
  const quizCatas = await call('GET', `/cs/courses/${quiz.id}/catalogs`, { token })
  const withQuiz = (quizCatas.json?.data || [])
    .flatMap((d) => d.sections || [])
    .filter((a) => (a.subjectNum || 0) > 0)
  check('★ 学习目录里能看到"有题的动作"（subjectNum > 0）',
    withQuiz.length === 1 && withQuiz[0].subjectNum === 3,
    withQuiz.map((a) => `${a.name}:${a.subjectNum}题`).join(' / ') || '（没有带题的动作）')
  if (!withQuiz.length) return

  const pulled = await call('GET', `/cs/practices/${withQuiz[0].id}`, { token })
  const subjects = pulled.json?.data || []
  check('★ 学员能拉到小测题目（GET /cs/practices/{cataId}）',
    subjects.length === 3, `${subjects.length} 道`)
  // ★★ 这条是"答案不能提前泄露"的契约：作答前接口**故意不下发** answer / analysis
  check('★★ 作答前不下发答案与解析（只有提交后才给）',
    subjects.every((s) => s.answer === undefined && s.analysis === undefined),
    subjects.some((s) => s.answer !== undefined) ? '⚠️ 有题目带着 answer 下来了' : '都没有 answer / analysis')
}

// ============================================================
// main
// ============================================================

async function main() {
  console.log('============================================================')
  console.log(' FitMind 演示数据 seed（M7-2 起的验收前置；M7-6 增加"动作小测"）')
  console.log(` 选项：${[OPT_RESET && '--reset', OPT_CLEAN && '--clean', OPT_STATS && '--stats'].filter(Boolean).join(' ') || '（无，仅补齐数据）'}`)
  console.log('============================================================')

  section('登录')
  const token = await login()
  ok('登录成功', `token 长度 ${token.length}`)

  if (OPT_RESET) {
    const resetOk = await resetCourses()
    if (!resetOk) {
      console.log('\n  ⛔ 清理未通过安全断言，已中止（没有删除任何数据）')
      process.exit(3)
    }
  }

  if (OPT_CLEAN) await disableGarbageCategories(token)

  const idOf = await ensureCategories(token)

  // 先批量查一次"哪些课程已存在"（一次请求拿全量），只给缺的那些上传封面
  const allRes = await call('GET', '/ss/courses/portal?pageNo=1&pageSize=100', { token })
  const existingNames = new Set((allRes.json?.data?.list || []).map((c) => c.name))
  const needCoverNames = COURSES.filter((c) => !existingNames.has(c.name)).map((c) => c.name)

  const { videos, covers } = await uploadAssets(token, needCoverNames)

  section('④ 创建课程（走完整建课流程：基本信息 → 目录 → 动作视频 → 教练 →〔配题〕→ 上架）')
  // ★ 教练 token 要在**建课循环之前**拿到：一门课（小测演示课）在"上架前"就要用它配题
  const coachToken = await loginStaff(COACH_PHONE)
  ok('教练登录成功（建题需要 staff 角色）')

  const results = []
  for (const spec of COURSES) {
    const r = await ensureCourse(token, spec, { idOf, videos, covers, coachToken })
    results.push({ name: spec.name, ...r })
    if (r.skipped) {
      console.log(`  ⏭ ${spec.name}（已存在，跳过）`)
    } else if (r.error) {
      check(spec.name, false, r.error)
    } else {
      console.log(`  ✅ ${spec.name}`)
    }
  }
  const createdN = results.filter((r) => r.id && !r.skipped).length
  const skippedN = results.filter((r) => r.skipped).length
  const errN = results.filter((r) => r.error).length
  ok('课程处理完成', `新建 ${createdN}，跳过 ${skippedN}，失败 ${errN}`)

  // 让学员报名"小测演示课"（免费课走的是报名接口，不是下单）
  //   ★ 目的：演示"我的课程 → 学习页 → 动作小测"这条链路时不用先手动报名
  const quizCourse = results.find((r) => r.name.startsWith('动作小测演示课'))
  if (quizCourse?.id) {
    const enrol = await call('POST', `/ls/lessons/${quizCourse.id}`, { token })
    ok('学员已报名小测演示课', `课程 id=${quizCourse.id}，code=${enrol.json?.code}`)
  }

  if (OPT_STATS) await applyStats()

  // 等 ES 索引同步（上架是 MQ 异步建索引）
  console.log('\n  ⏳ 等待 ES 索引同步（上架 / 索引刷新都是 MQ 异步的）…')
  await new Promise((r) => setTimeout(r, 4000))

  await verify(token)

  console.log('\n============================================================')
  console.log(` 结果：${passed} 通过 / ${failed} 失败`)
  if (failures.length) {
    console.log(' 失败项：')
    failures.forEach((f) => console.log(`   · ${f}`))
  }
  console.log('============================================================')
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('脚本异常：', e)
  process.exit(2)
})
