/**
 * ============================================================================
 * RAG 检索评估集（固定问答对）
 * ============================================================================
 *
 * ★ 为什么必须有这个东西：
 *   RAG 调参最容易滑向玄学 —— 改一个阈值，看看回答"感觉好点了没"。
 *   有了固定的问题集 + 每个问题"应该命中哪篇语料"，
 *   "改了参数命中率从 13/20 变成 17/20"就是一个可比较的数字。
 *
 *   **没有评估集的 RAG 调参就是瞎调。**
 *
 * ★ 用法：
 *   - `scripts/accept-m6.mjs` 的「检索评估集」分组会用它跑一遍并断言命中率
 *   - `node scripts/rag-eval-rag.mjs`（见 scripts/rag-eval.mjs）可以单独跑，
 *     用来对比不同 topK / 阈值 / 是否混合检索
 *
 * ⚠️ 期望命中的是 **docId**（语料文件名去掉 .md），不是标题文本 ——
 *   docId 是稳定标识，标题改动不会让它失效。
 *
 * ★★ <b>每条问题都必须声明 `goal`（训练目标视角）—— 这是 M6-4 之后必须加的。</b>
 *   M6-3 时检索与用户无关，同一句话对谁都返回同样的结果；
 *   <b>M6-4 起检索会按用户的训练目标做元数据过滤</b>（减脂用户不该拿到"增肌期怎么吃"）。
 *   于是"正确的结果"变成了<b>因人而异</b>：
 *   一个增肌用户去问"减脂期热量缺口开多大"，本来就<b>不应该</b>命中减脂文档 ——
 *   那不是检索坏了，是个性化生效了。
 *
 *   → 所以评估集必须把"这是谁的视角"写进去。反过来说：
 *     <b>评估集不声明用户视角，接了个性化之后它就会开始莫名掉分，
 *     而人会去调阈值 —— 越调越错。</b>
 *
 * ⚠️ 这些问题与被测语料是**同一批**写出来的（项目自建语料），
 *   所以命中率反映的是"检索链路是否正常工作"，不代表真实用户提问的难度。
 *   真实场景需要的是从线上日志里捞问题再人工标注 —— 那是另一个量级的工作。
 */
export const RAG_EVAL_SET = [
  // ---- 01 训练原则 ----
  { q: '什么是渐进超负荷，怎么加重', expectDoc: '01-training-principles', goal: 'muscle_gain' },
  { q: '每个肌群每周练多少组比较合适', expectDoc: '01-training-principles', goal: 'muscle_gain' },
  { q: '训练时留余次数 RIR 是什么意思', expectDoc: '01-training-principles', goal: 'muscle_gain' },
  // ---- 02 新手错误 ----
  { q: '新手练健身最容易犯哪些错误', expectDoc: '02-beginner-mistakes', goal: 'muscle_gain' },
  { q: '练完肌肉酸痛是不是正常的', expectDoc: '02-beginner-mistakes', goal: 'muscle_gain' },
  // ---- 03 动作要领 ----
  { q: '高脚杯深蹲的动作要领是什么', expectDoc: '03-exercise-technique', goal: 'muscle_gain' },
  { q: '罗马尼亚硬拉应该怎么下放', expectDoc: '03-exercise-technique', goal: 'muscle_gain' },
  { q: '单臂哑铃划船怎么发力才对', expectDoc: '03-exercise-technique', goal: 'muscle_gain' },
  // ---- 04 增肌 ----
  { q: '增肌期热量盈余要多少合适', expectDoc: '04-muscle-gain', goal: 'muscle_gain' },
  { q: '瘦人吃不胖怎么才能增重', expectDoc: '04-muscle-gain', goal: 'muscle_gain' },
  // ---- 05 蛋白质 ----
  { q: '增肌期每天该吃多少蛋白质', expectDoc: '05-protein-intake', goal: 'muscle_gain' },
  { q: '蛋白质每餐吃多少吸收最好', expectDoc: '05-protein-intake', goal: 'muscle_gain' },
  // ---- 06 热量与 TDEE ----
  { q: 'TDEE 每日总消耗怎么算', expectDoc: '06-calories-tdee', goal: 'muscle_gain' },
  { q: '基础代谢的公式是什么', expectDoc: '06-calories-tdee', goal: 'muscle_gain' },
  // ---- 07 减脂 ----
  { q: '减脂期热量缺口开多大合适', expectDoc: '07-fat-loss', goal: 'fat_loss' },
  { q: '减脂平台期体重一直不动怎么办', expectDoc: '07-fat-loss', goal: 'fat_loss' },
  { q: '减脂期只做有氧不练力量可以吗', expectDoc: '07-fat-loss', goal: 'fat_loss' },
  // ---- 08 餐次安排 ----
  { q: '训练前应该提前多久吃东西', expectDoc: '08-meal-timing', goal: 'muscle_gain' },
  { q: '睡前能不能喝牛奶补蛋白', expectDoc: '08-meal-timing', goal: 'muscle_gain' },
  // ---- 09 恢复与睡眠 ----
  { q: '睡眠对增肌有什么影响', expectDoc: '09-recovery-sleep', goal: 'muscle_gain' },
  { q: '恢复不足会有哪些信号', expectDoc: '09-recovery-sleep', goal: 'muscle_gain' },
  // ---- 10 减量 ----
  { q: '减量周应该怎么安排', expectDoc: '10-deload', goal: 'muscle_gain' },
  { q: '什么时候需要安排一个减量周', expectDoc: '10-deload', goal: 'muscle_gain' },
  // ---- 11 损伤预防 ----
  { q: '深蹲的时候膝盖疼怎么办', expectDoc: '11-injury-prevention', goal: 'muscle_gain' },
  { q: '卧推肩膀疼是哪里出了问题', expectDoc: '11-injury-prevention', goal: 'muscle_gain' },
  // ---- 12 热身与有氧 ----
  { q: '训练前的热身应该做哪些内容', expectDoc: '12-warmup-cardio', goal: 'muscle_gain' },
  { q: '有氧和力量训练哪个先做', expectDoc: '12-warmup-cardio', goal: 'muscle_gain' },
];

/**
 * ★ 拒答用例：这些问题在知识库里**绝对没有**答案。
 *   期望是"检索命中 0 条 + 回答明确说不知道"，而不是编一段听起来合理的健身内容。
 *
 * ⚠️ 选的都是**领域外**问题（修车、编程、股票）——
 *   不要用"健身领域内但语料没写"的问题（比如"健美备赛怎么脱水"）：
 *   那种问题语义上和语料很接近，向量检索会命中"看起来相关"的内容，
 *   那是阈值该不该调的问题，不是拒答有没有生效的问题。两类要分开测。
 */
export const RAG_REFUSAL_SET = [
  { q: '怎么修汽车发动机', why: '领域外' },
  { q: '帮我写一段 Python 冒泡排序', why: '领域外' },
  { q: '今天上证指数收盘多少点', why: '领域外' },
];
