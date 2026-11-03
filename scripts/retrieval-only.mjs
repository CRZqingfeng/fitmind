#!/usr/bin/env node
/**
 * ============================================================================
 * 只测检索 —— 不调用生成模型，成本约 0.0003 元/27 题
 * ============================================================================
 *
 * 用法：
 *   node scripts/retrieval-only.mjs                 # 27 题
 *   node scripts/retrieval-only.mjs --verify        # 额外与真实链路采集结果比对
 *   node scripts/retrieval-only.mjs --limit=5
 *   node scripts/retrieval-only.mjs --compare=path/to/collected.json
 *
 * ⚠️ 前置：fm-es 容器在跑（9201），且知识库索引已建；不需要 fm-aigc、不需要网关、不需要 Nacos
 *
 * ★ 它和 rag-eval.mjs 的分工：
 *   rag-eval.mjs     走真实对话接口，会调用大模型生成回答（每题都花钱），断言的是"回答里有没有引用对的语料"
 *   本脚本           只做「问题 → embedding → ES 检索」，**完全不碰生成模型**，
 *                    断言的是"检索这一半有没有把该给的文档捞回来"
 *
 *   改检索参数（topK / 阈值 / 混合开关 / 分块 / 语料）之后想快速回归，用这个：
 *   一次全量 27 题的成本是 embedding 的几分钱，可以随便跑。
 *
 * ★ 复刻的是 fm-aigc 的真实实现（ElasticsearchVectorStore + KnowledgeRetriever）：
 *   · 向量路：script_score「cosineSimilarity(params.queryVector,'embedding') + 1.0」
 *             min_score = 阈值 + 1.0（★ 在取 topK 之前生效，不是取回来再筛）
 *             size = max(topK, vector-top-k=8)
 *   · 关键词路：match content，minimum_should_match 60%，size = max(topK, 6)
 *   · 融合：RRF，score = Σ 1/(60 + rank + 1)，取前 topK=4
 *   · goal 过滤：term(goal == 用户目标) OR term(goal == general)
 *   实测与真实链路采集结果的 Jaccard 一致率 100%（27 题），所以它测的就是线上那条路。
 * ============================================================================
 */
import fs from 'node:fs';

const ES = 'http://127.0.0.1:9201/fitmind_knowledge_text_embedding_v4_1024/_search';
const EMB = 'https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings';

// —— 与 application.yml 的 fm.ai.rag 保持一致 ——
const TOP_K = 4;
const VECTOR_TOPK = 8;
const KEYWORD_TOPK = 6;
const THRESHOLD = 0.55;
const RRF_K = 60;

const args = process.argv.slice(2);
const val = (n) => args.find((a) => a.startsWith(`--${n}=`))?.split('=')[1];
const LIMIT = val('limit') ? Number(val('limit')) : null;
const VERIFY = args.includes('--verify') || !!val('compare');
const COMPARE_FILE = val('compare') || '.tmp/ragas/collected.json';

const DS_KEY = (fs.readFileSync('docker/.env', 'utf8').match(/^DASHSCOPE_API_KEY=(.*)$/m) || [])[1]?.trim().replace(/^["']|["']$/g, '');
if (!DS_KEY) {
  console.error('  ✗ docker/.env 里没有 DASHSCOPE_API_KEY，无法调用 embedding');
  process.exit(1);
}

/** 27 条评估题：问题 → 期望命中的 docId → 提问者视角 */
const EVAL_SET = [
  ['什么是渐进超负荷，怎么加重', '01-training-principles', 'muscle_gain'],
  ['每个肌群每周练多少组比较合适', '01-training-principles', 'muscle_gain'],
  ['训练时留余次数 RIR 是什么意思', '01-training-principles', 'muscle_gain'],
  ['新手练健身最容易犯哪些错误', '02-beginner-mistakes', 'muscle_gain'],
  ['练完肌肉酸痛是不是正常的', '02-beginner-mistakes', 'muscle_gain'],
  ['高脚杯深蹲的动作要领是什么', '03-exercise-technique', 'muscle_gain'],
  ['罗马尼亚硬拉应该怎么下放', '03-exercise-technique', 'muscle_gain'],
  ['单臂哑铃划船怎么发力才对', '03-exercise-technique', 'muscle_gain'],
  ['增肌期热量盈余要多少合适', '04-muscle-gain', 'muscle_gain'],
  ['瘦人吃不胖怎么才能增重', '04-muscle-gain', 'muscle_gain'],
  ['增肌期每天该吃多少蛋白质', '05-protein-intake', 'muscle_gain'],
  ['蛋白质每餐吃多少吸收最好', '05-protein-intake', 'muscle_gain'],
  ['TDEE 每日总消耗怎么算', '06-calories-tdee', 'muscle_gain'],
  ['基础代谢的公式是什么', '06-calories-tdee', 'muscle_gain'],
  ['减脂期热量缺口开多大合适', '07-fat-loss', 'fat_loss'],
  ['减脂平台期体重一直不动怎么办', '07-fat-loss', 'fat_loss'],
  ['减脂期只做有氧不练力量可以吗', '07-fat-loss', 'fat_loss'],
  ['训练前应该提前多久吃东西', '08-meal-timing', 'muscle_gain'],
  ['睡前能不能喝牛奶补蛋白', '08-meal-timing', 'muscle_gain'],
  ['睡眠对增肌有什么影响', '09-recovery-sleep', 'muscle_gain'],
  ['恢复不足会有哪些信号', '09-recovery-sleep', 'muscle_gain'],
  ['减量周应该怎么安排', '10-deload', 'muscle_gain'],
  ['什么时候需要安排一个减量周', '10-deload', 'muscle_gain'],
  ['深蹲的时候膝盖疼怎么办', '11-injury-prevention', 'muscle_gain'],
  ['卧推肩膀疼是哪里出了问题', '11-injury-prevention', 'muscle_gain'],
  ['训练前的热身应该做哪些内容', '12-warmup-cardio', 'muscle_gain'],
  ['有氧和力量训练哪个先做', '12-warmup-cardio', 'muscle_gain'],
];

async function embed(text) {
  const res = await fetch(EMB, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${DS_KEY}` },
    body: JSON.stringify({ model: 'text-embedding-v4', input: [text], dimensions: 1024 }),
  });
  const json = await res.json();
  if (!res.ok) throw new Error(`embedding ${res.status}: ${JSON.stringify(json).slice(0, 160)}`);
  return json.data[0].embedding;
}

const goalFilter = (goal) => ({
  bool: { should: [{ term: { goal } }, { term: { goal: 'general' } }], minimum_should_match: 1 },
});

async function esSearch(payload, label) {
  const res = await fetch(ES, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  const json = await res.json();
  if (!res.ok) throw new Error(`${label} ${res.status}: ${JSON.stringify(json).slice(0, 200)}`);
  return json.hits.hits.map((h) => ({
    id: h._id,
    doc: h._source.doc_id,
    section: h._source.section,
    score: label === '向量' ? h._score - 1.0 : h._score, // 向量路 ES 侧 +1 过，这里还原
  }));
}

async function vectorSearch(q, goal) {
  return esSearch({
    size: VECTOR_TOPK,
    _source: ['doc_id', 'title', 'section'],
    min_score: THRESHOLD + 1.0,
    query: {
      script_score: {
        query: { bool: { must: { match_all: {} }, filter: goalFilter(goal) } },
        script: {
          source: "cosineSimilarity(params.queryVector, 'embedding') + 1.0",
          params: { queryVector: await embed(q) },
        },
      },
    },
  }, '向量');
}

async function keywordSearch(q, goal) {
  return esSearch({
    size: KEYWORD_TOPK,
    _source: ['doc_id', 'title', 'section'],
    query: {
      bool: {
        must: [{ match: { content: { query: q, minimum_should_match: '60%' } } }],
        filter: [goalFilter(goal)],
      },
    },
  }, '关键词');
}

/** RRF：只用名次，不用分数（两路分数量纲不可比） */
function rrf(lists, k = TOP_K) {
  const fused = new Map();
  const docs = new Map();
  for (const list of lists) {
    list.forEach((item, rank) => {
      fused.set(item.id, (fused.get(item.id) || 0) + 1.0 / (RRF_K + rank + 1));
      if (!docs.has(item.id)) docs.set(item.id, item);
    });
  }
  return [...fused.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, k)
    .map(([id, rrfScore]) => ({ ...docs.get(id), rrf: Number(rrfScore.toFixed(4)) }));
}

async function main() {
  console.log('='.repeat(70));
  console.log(' FitMind 检索回归（只测检索，不调用生成模型）');
  console.log('='.repeat(70));
  console.log(`  topK=${TOP_K} 阈值=${THRESHOLD} 混合=开  → 每题计算一次 embedding\n`);

  const items = LIMIT ? EVAL_SET.slice(0, LIMIT) : EVAL_SET;

  let real = {};
  if (VERIFY) {
    try {
      const rows = JSON.parse(fs.readFileSync(COMPARE_FILE, 'utf8'));
      real = Object.fromEntries(rows.map((r) => [r.idx, (r.source_meta || []).map((m) => m.docId)]));
    } catch {
      console.log('  （未找到真实链路采集结果，跳过一致性校验）\n');
    }
  }

  const t0 = Date.now();
  const rows = [];
  for (const [i, [q, expect, goal]] of items.entries()) {
    const t = Date.now();
    const [v, k] = await Promise.all([vectorSearch(q, goal), keywordSearch(q, goal)]);
    const fused = rrf([v, k]);
    const docs = fused.map((d) => d.doc);
    const hit = docs.includes(expect);
    const unique = new Set(docs).size;
    rows.push({ idx: i + 1, q, expect, goal, docs, hit, vectorHits: v.length, keywordHits: k.length, unique, ms: Date.now() - t });

    console.log(
      `  ${hit ? '✓' : '✗'} #${String(i + 1).padStart(2, '0')} ` +
      `向量${String(v.length).padStart(2)} 关键词${String(k.length).padStart(2)} → ${unique} 篇  ` +
      `[${docs.join(',') || '无'}]  ${q.slice(0, 16)}`
    );
  }

  const n = rows.length;
  const hitCount = rows.filter((r) => r.hit).length;
  const kwEmpty = rows.filter((r) => r.keywordHits === 0).length;
  const avgUnique = rows.reduce((s, r) => s + r.unique, 0) / n;
  const sameDoc = rows.filter((r) => r.unique === 1).length;
  const avgMs = rows.reduce((s, r) => s + r.ms, 0) / n;

  console.log('\n' + '='.repeat(70));
  console.log(`  检索命中率            ${hitCount}/${n} = ${(hitCount / n * 100).toFixed(1)}%`);
  console.log(`  平均来源篇数          ${avgUnique.toFixed(1)} 篇（同文档占满 topK：${sameDoc} 题）`);
  console.log(`  关键词路 0 召回       ${kwEmpty}/${n} 题（minimum_should_match 60% 在长句上偏严）`);
  console.log(`  平均单题耗时          ${avgMs.toFixed(0)} ms`);
  console.log(`  本次成本              约 ${(n * 20 * 0.0005 / 1000).toFixed(4)} 元（${n} 次 embedding）`);

  if (VERIFY && Object.keys(real).length) {
    const jac = [];
    for (const r of rows) {
      const a = new Set(r.docs);
      const b = new Set(real[r.idx] || []);
      if (!b.size) continue;
      const inter = [...a].filter((x) => b.has(x)).length;
      jac.push(inter / (a.size + b.size - inter));
    }
    console.log(`  与真实链路一致率      ${(jac.reduce((s, x) => s + x, 0) / jac.length * 100).toFixed(1)}%` +
                `（Jaccard，${jac.length} 题可比）`);
  }

  console.log(`  总耗时                ${((Date.now() - t0) / 1000).toFixed(1)} s`);
  console.log('='.repeat(70));

  const miss = rows.filter((r) => !r.hit);
  if (miss.length) {
    console.log('\n  未命中明细：');
    miss.forEach((r) => console.log(`    #${String(r.idx).padStart(2, '0')} ${r.q}  期望 ${r.expect}，实际 ${r.docs.join(',') || '无'}`));
  }

  fs.mkdirSync('.tmp/ragas', { recursive: true });
  fs.writeFileSync('.tmp/ragas/retrieval-only.json', JSON.stringify(rows, null, 1));
  console.log('\n  明细已保存：.tmp/ragas/retrieval-only.json');
}

main().catch((e) => {
  console.error('\n  ✗', e.message);
  process.exit(1);
});
