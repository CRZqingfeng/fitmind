#!/usr/bin/env node
/**
 * ============================================================================
 * RAG 检索评估 —— 用固定问答集衡量"检索参数改了之后效果变好还是变坏"
 * ============================================================================
 *
 * 用法：
 *   node scripts/rag-eval.mjs                 # 跑默认的三组对比
 *   node scripts/rag-eval.mjs --topK=4        # 只跑一组（可叠加 threshold / hybrid）
 *   node scripts/rag-eval.mjs --quiet         # 只打印汇总表
 *
 * ⚠️ 前置：fm-aigc 已启动，且知识库索引已构建
 *   （`bash scripts/start-all.sh fm-aigc mock`，首启会自动建索引）
 *
 * ★ 它为什么重要：
 *   没有评估集的 RAG 调参就是瞎调 —— 改一个阈值，看看回答"感觉好点了没"，
 *   这种判断无法积累、也无法复现。
 *   有了固定问答对 + 每个问题应该命中哪篇语料，
 *   "命中率从 22/27 变成 25/27"就是一个能写进文档、能被别人复核的数字。
 *
 * ⚠️ 本脚本断言的是"**检索命中**"（期望的语料出现在引用来源里），
 *   不是"回答内容对不对"。后者要靠人工或另一个模型来评 ——
 *   混在一起会让两个问题互相掩盖。
 * ============================================================================
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import { RAG_EVAL_SET, RAG_REFUSAL_SET } from './rag-eval-set.mjs';

const GATEWAY = 'http://localhost:10010';
// ★ M6-5：评估会新建几十个会话，每个都写了 Redis 会话记忆 —— 清理时要连它一起清，
//   否则 Redis 里会留下上百个要等 7 天 TTL 才消失的 key（实测残留 314 个）
const REDIS_PASSWORD = (fs.readFileSync('docker/.env', 'utf8').match(/^REDIS_PASSWORD=(.*)$/m) || [])[1]?.trim();
const MYSQL = 'C:////Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe';
const PHONE = '13800010001';
const STUDENT_USER_ID = 1;
const HIT_RATE_FLOOR = 0.75;

const args = process.argv.slice(2);
const quiet = args.includes('--quiet');
const argVal = (name) => {
  const hit = args.find((a) => a.startsWith(`--${name}=`));
  return hit ? hit.split('=')[1] : undefined;
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function call(method, path, { body, token } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) headers['Authorization'] = token;
  const res = await fetch(GATEWAY + path, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON */ }
  return { status: res.status, json, text };
}

/**
 * ★ 评估会产生大量会话（每组配置 N 条提问 = N 个会话）。
 *   不清理的话，跑几次评估就会在自己的库里堆出上千条测试会话 ——
 *   而"评估数据污染业务表"是那种要等到做数据分析时才会发现的问题。
 *   所以脚本自己负责收尾，不留给人记。
 */
function cleanup() {
  // ★ 必须在删 MySQL 之前把 session_id 取出来（记忆 key 是按 session_id 命名的）
  const sids = (spawnSync(MYSQL,
    ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e',
      `SELECT session_id FROM fm_aigc.chat_session WHERE user_id=${STUDENT_USER_ID}`],
    { encoding: 'utf8' }).stdout || '').split(/\r?\n/).map((x) => x.trim()).filter(Boolean);
  // ★ 批量 DEL：评估一次会产生上百个会话（每个会话 2~3 个 key），
  //   逐个 docker exec 要几分钟纯等待 —— 批量化之后只要几次调用。
  const memKeys = sids.flatMap((sid) => [
    `aigc:mem:${sid}`, `aigc:mem:summary:${sid}`, `aigc:gen:${sid}`,
  ]);
  for (let i = 0; i < memKeys.length; i += 100) {
    spawnSync('docker',
      ['exec', 'fm-redis', 'redis-cli', '-a', REDIS_PASSWORD, '--no-auth-warning',
        'DEL', ...memKeys.slice(i, i + 100)],
      { encoding: 'utf8' });
  }
  for (const q of [
    `DELETE FROM fm_aigc.chat_message WHERE user_id=${STUDENT_USER_ID}`,
    `DELETE FROM fm_aigc.chat_session WHERE user_id=${STUDENT_USER_ID}`,
  ]) {
    spawnSync(MYSQL,
      ['--default-character-set=utf8mb4', '-h127.0.0.1', '-P3306', '-uroot', '-p1234', '-N', '-e', q],
      { encoding: 'utf8' });
  }
}

async function login() {
  const code = (await call('POST', `/us/codes?phone=${PHONE}`)).json?.data;
  const r = await call('POST', '/as/accounts/login', {
    body: { type: 2, cellPhone: PHONE, password: String(code), rememberMe: false },
  });
  return r.json?.data;
}

/**
 * 跑一组参数下的评估集。
 *
 * @returns {Promise<{hit:number,total:number,miss:string[],refused:number,refusalTotal:number}>}
 */
async function runConfig(name, token, opts) {
  const { topK, threshold, hybrid } = opts;
  const body = { chatType: 'ENHANCE' };
  if (topK !== undefined) body.ragTopK = Number(topK);
  if (threshold !== undefined) body.ragThreshold = Number(threshold);
  if (hybrid !== undefined) body.ragHybrid = String(hybrid) === 'true';

  const miss = [];
  let hit = 0;
  for (const item of RAG_EVAL_SET) {
    // ★ 带上该问题对应的用户视角（goal）：检索是个性化的，不声明视角评估就不公平
    const r = await call('POST', '/ais/chat', { body: { ...body, content: item.q, goal: item.goal }, token });
    const sources = r.json?.data?.rag?.sources || [];
    const docIds = sources.map((s) => s.docId);
    const ok = docIds.includes(item.expectDoc);
    if (ok) {
      hit++;
    } else {
      miss.push(`${item.q} → 命中[${docIds.join(',') || '无'}]，期望 ${item.expectDoc}`);
    }
    if (!quiet) {
      console.log(`    ${ok ? '✓' : '✗'} ${item.q}${ok ? '' : ` （期望 ${item.expectDoc}，实际 ${docIds.join(',') || '无'}）`}`);
    }
  }

  let refused = 0;
  for (const item of RAG_REFUSAL_SET) {
    const r = await call('POST', '/ais/chat', { body: { ...body, content: item.q }, token });
    const sources = r.json?.data?.rag?.sources || [];
    const ok = sources.length === 0;
    if (ok) refused++;
    if (!quiet) {
      console.log(`    ${ok ? '✓' : '✗'} [拒答] ${item.q} → 检索 ${sources.length} 条`);
    }
  }

  return { name, hit, total: RAG_EVAL_SET.length, miss, refused, refusalTotal: RAG_REFUSAL_SET.length };
}

async function main() {
  console.log('='.repeat(74));
  console.log(' FitMind RAG 检索评估');
  console.log('='.repeat(74));

  const token = await login();
  if (!token) {
    console.error('  ✗ 登录失败，请确认 fm-auth / 网关正常，且手机号未触发验证码限流');
    process.exit(1);
  }

  const single = args.some((a) => a.startsWith('--topK=') || a.startsWith('--threshold=') || a.startsWith('--hybrid='));
  const configs = single
    ? [{ name: '自定义', topK: argVal('topK'), threshold: argVal('threshold'), hybrid: argVal('hybrid') }]
    : [
        { name: '默认（混合 topK=4 阈值0.55）', topK: undefined, threshold: undefined, hybrid: undefined },
        { name: '纯向量（关混合）', topK: undefined, threshold: undefined, hybrid: false },
        { name: '只给 1 条（topK=1）', topK: 1, threshold: undefined, hybrid: undefined },
        { name: '阈值收紧到 0.75', topK: undefined, threshold: 0.75, hybrid: undefined },
      ];

  const results = [];
  for (const cfg of configs) {
    console.log(`\n【${cfg.name}】`);
    results.push(await runConfig(cfg.name, token, cfg));
    await sleep(200);
  }

  console.log('\n' + '='.repeat(74));
  console.log(' 汇总');
  console.log('='.repeat(74));
  console.log(`  ${'配置'.padEnd(30)}${'命中率'.padEnd(14)}${'拒答正确'}`);
  for (const r of results) {
    const rate = ((r.hit / r.total) * 100).toFixed(1);
    console.log(`  ${r.name.padEnd(28)}${`${r.hit}/${r.total} (${rate}%)`.padEnd(16)}${r.refused}/${r.refusalTotal}`);
  }

  const baseline = results[0];
  const rate = baseline.hit / baseline.total;
  console.log(`\n  基准配置命中率下限：${(HIT_RATE_FLOOR * 100).toFixed(0)}%，实际 ${(rate * 100).toFixed(1)}%`);
  if (baseline.miss.length) {
    console.log('\n  基准配置未命中的问题：');
    baseline.miss.forEach((m, i) => console.log(`    ${i + 1}. ${m}`));
  }
  cleanup();
  console.log('  评估产生的会话与消息已清理');
  console.log('='.repeat(74));
  process.exit(rate >= HIT_RATE_FLOOR ? 0 : 1);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
