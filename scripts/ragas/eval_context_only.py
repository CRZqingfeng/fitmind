# -*- coding: utf-8 -*-
"""只评"上下文质量"（precision / recall）—— 不需要生成回答。

★ 为什么这个脚本重要：RAGAS 里 **ContextPrecision / ContextRecall 只吃
「问题 + 注入的上下文 + 参考答案」**，跟模型生成的那段话无关。
所以"调检索参数（topK / 配额 / 重排下限 / 阈值）"这类实验，可以
**完全不调用生成模型** —— 成本只剩 embedding 的一次调用（约 1e-5 元/题）。

输入是 `retrieval-only.mjs --dump=xxx.json` 的产物（含每条的上下文正文）。
判官用 GLM-5.3-Flash（赠送额度，成本 0）。

用法：
  node scripts/retrieval-only.mjs --topk=6 --dump=.tmp/ragas/dump_k6.json
  python .tmp/ragas/eval_context_only.py --dump=.tmp/ragas/dump_k6.json --tag=k6
"""
import asyncio, json, os, re, sys, time
import statistics as st
from openai import AsyncOpenAI
from ragas.llms import llm_factory
from ragas.metrics.collections import ContextPrecision, ContextRecall

# ★ 参考答案来源：默认用 fitmind 的 reference_set；换项目时用 --reference=xxx.json
#   （{"问题原文": "参考答案"} 的字典，或 [{"q":..., "reference":...}] 列表）
_REF_FILE = next((a.split("=", 1)[1] for a in sys.argv if a.startswith("--reference=")), None)
if _REF_FILE:
    _raw = json.load(open(_REF_FILE, encoding="utf-8"))
    REFERENCE = _raw if isinstance(_raw, dict) else {r["q"]: r["reference"] for r in _raw}
else:
    sys.path.insert(0, ".tmp/ragas")
    from reference_set import REFERENCE  # noqa: E402

JUDGE_MODEL = "glm-5.3-flash"
JUDGE_BASE = "https://open.bigmodel.cn/api/paas/v4"

_arg = lambda k, d: next((a.split("=", 1)[1] for a in sys.argv if a.startswith(f"--{k}=")), d)
DUMP = _arg("dump", ".tmp/ragas/dump.json")
TAG = _arg("tag", "run")
OUT = _arg("out", f".tmp/ragas/ctx_{TAG}.json")
PARTIAL = _arg("partial", f".tmp/ragas/ctx_{TAG}.partial.jsonl")
CONC = int(_arg("conc", "6"))
# ★ 与 RAGAS 主评估对齐用：只跑同样那 10 题（1,4,6,9,11,13,15,18,20,22）
IDX = _arg("idx", "")

ZHIPU_KEY = os.getenv("ZHIPU_API_KEY", "").strip() or open(".tmp/ragas/.zhipu_key", encoding="utf-8").read().strip()

# ★ 同 eval_glm.py：该模型关不掉思考，只能用 reasoning_effort=low 压到最少；
#   max_tokens 必须给足，否则思考 token 吃光预算 -> content 空 -> 伪装成 JSON 解析失败
llm = llm_factory(
    model=JUDGE_MODEL,
    client=AsyncOpenAI(api_key=ZHIPU_KEY, base_url=JUDGE_BASE, timeout=300),
    max_tokens=32768,
    temperature=0,
    reasoning_effort="low",
)
M = {"context_precision": ContextPrecision(llm=llm), "context_recall": ContextRecall(llm=llm)}


async def one(name, metric, row, sem):
    async with sem:
        t0 = time.time()
        try:
            r = await metric.ascore(user_input=row["q"], reference=row["reference"],
                                    retrieved_contexts=row["retrieved_contexts"])
            return {"score": round(float(getattr(r, "value", r)), 4), "sec": round(time.time() - t0, 1), "error": None}
        except Exception as e:
            return {"score": None, "sec": round(time.time() - t0, 1), "error": repr(e)[:300]}


def read_partial():
    out = {}
    if os.path.exists(PARTIAL):
        for line in open(PARTIAL, encoding="utf-8"):
            line = line.strip()
            if line:
                d = json.loads(line)
                out[(d["idx"], d["metric"])] = d["result"]
    return out


async def main():
    raw = json.load(open(DUMP, encoding="utf-8"))
    if IDX:
        pick = {int(x) for x in IDX.split(",")}
        raw = [r for r in raw if r["idx"] in pick]
    rows = []
    for r in raw:
        ref = REFERENCE.get(r["q"])
        if not ref:
            print(f"  ⚠️ 缺少参考答案，跳过 #{r['idx']} {r['q'][:16]}")
            continue
        rows.append({
            "idx": r["idx"], "q": r["q"], "expect": r.get("expect"),
            "reference": ref,
            "retrieved_contexts": [c["text"] for c in r["contexts"]],
            "n_ctx": len(r["contexts"]),
            "docs": [c["doc"] for c in r["contexts"]],
        })

    done = read_partial()
    todo = [(r["idx"], n, m, r) for r in rows for n, m in M.items() if (r["idx"], n) not in done]
    print(f"tag={TAG} 判官={JUDGE_MODEL} 题数={len(rows)} 待跑={len(todo)}", flush=True)

    sem = asyncio.Semaphore(CONC)
    lock = asyncio.Lock()
    t0 = time.time()
    cnt = {"n": 0}

    async def run(t):
        idx, n, m, row = t
        res = await one(n, m, row, sem)
        async with lock:
            with open(PARTIAL, "a", encoding="utf-8") as f:
                f.write(json.dumps({"idx": idx, "metric": n, "result": res}, ensure_ascii=False) + "\n")
            cnt["n"] += 1
            if cnt["n"] % 10 == 0 or cnt["n"] == len(todo):
                print(f"  {cnt['n']}/{len(todo)}  ({(time.time()-t0)/60:.1f} min)", flush=True)

    if todo:
        await asyncio.gather(*[run(t) for t in todo])

    allres = read_partial()
    for r in rows:
        r["scores"] = {n: allres.get((r["idx"], n), {"score": None})["score"] for n in M}
    json.dump(rows, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    print(f"\n耗时 {(time.time()-t0)/60:.1f} min -> {OUT}")
    print(f"\n{'指标':<20}{'均值':>8}{'达标题数':>10}{'平均注入条数':>14}")
    for n in M:
        vs = [r["scores"][n] for r in rows if r["scores"][n] is not None]
        thr = 0.85 if n == "context_precision" else 0.70
        ok = sum(1 for v in vs if v >= thr)
        print(f"{n:<20}{st.mean(vs):>8.3f}{f'{ok}/{len(vs)}':>10}{st.mean([r['n_ctx'] for r in rows]):>14.1f}")


asyncio.run(main())
