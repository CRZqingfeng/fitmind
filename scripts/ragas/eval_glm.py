# -*- coding: utf-8 -*-
"""用 GLM 当判官的 RAGAS 评估（六指标，可切换四组配置做同口径对比）。

为什么换判官：原来的判官是 deepseek-flash，而回答也是 deepseek-flash 生成的 ——
自评偏差（self-preference）会让分数系统性偏高。GLM-5.3-Flash 属另一个家族，
且智谱赠送额度足够，判官成本为 0。

★ 关键坑（实测）：
  · glm-5.3-flash **始终思考**，`thinking={"type":"disabled"}` 会 400；
    必须用 `reasoning_effort="low"`（1.3s / 零思考 token，实测 JSON 可解析）。
  · 不传 reasoning_effort 时默认思考 5.9s / 236 思考 token —— 慢且费额度。
  · max_tokens 必须给足：思考 token 会先吃掉预算，太小会让 content 变成空字符串，
    表现成"JSON 解析失败"，很容易被误判成模型不会 JSON。

用法：
  python eval_glm.py --in=collected_p6.json --out=ragas_glm_p6.json --conc=6
  python eval_glm.py --in=... --out=... --metrics=faithfulness,context_precision  # 只跑子集
"""
import asyncio, json, os, re, sys, time
from openai import AsyncOpenAI
from ragas.llms import llm_factory
from ragas.embeddings.base import embedding_factory
from ragas.metrics.collections import (
    Faithfulness, AnswerRelevancy, ContextPrecision, ContextRecall,
    ContextEntityRecall, NoiseSensitivity,
)

JUDGE_MODEL = "glm-5.3-flash"
JUDGE_BASE = "https://open.bigmodel.cn/api/paas/v4"

_arg = lambda k, d: next((a.split("=", 1)[1] for a in sys.argv if a.startswith(f"--{k}=")), d)
INPUT = _arg("in", ".tmp/ragas/collected_p6.json")
OUTPUT = _arg("out", ".tmp/ragas/ragas_glm.json")
PARTIAL = _arg("partial", OUTPUT.replace(".json", ".partial.jsonl"))
CONC = int(_arg("conc", "6"))
LIMIT = int(_arg("limit", "0")) or None
METRICS = _arg("metrics", "faithfulness,answer_relevancy,context_precision,context_recall,context_entity_recall,noise_sensitivity")

def _judge_key():
    """判官密钥：优先环境变量 ZHIPU_API_KEY，其次 .tmp/ragas/.zhipu_key。
    ★ 换项目时只设环境变量即可，不必带文件。"""
    k = os.getenv("ZHIPU_API_KEY", "").strip()
    if k:
        return k
    for f in (".tmp/ragas/.zhipu_key", ".ragas/zhipu_key"):
        if os.path.exists(f):
            return open(f, encoding="utf-8").read().strip()
    raise SystemExit("缺少判官密钥：请设 ZHIPU_API_KEY，或写入 .tmp/ragas/.zhipu_key")


ZHIPU_KEY = _judge_key()


def env_key(name):
    """embedding 密钥：优先环境变量，其次 docker/.env（fitmind 的约定）。"""
    v = os.getenv(name, "").strip()
    if v:
        return v
    if os.path.exists("docker/.env"):
        for line in open("docker/.env", encoding="utf-8"):
            m = re.match(rf"^\s*{name}\s*=\s*(.+)$", line)
            if m:
                return m.group(1).strip().strip('"').strip("'")
    return None


# ★ max_tokens 给足 + reasoning_effort=low：关不掉思考，就让它想得最少
llm = llm_factory(
    model=JUDGE_MODEL,
    client=AsyncOpenAI(api_key=ZHIPU_KEY, base_url=JUDGE_BASE, timeout=300),
    max_tokens=32768,
    temperature=0,
    reasoning_effort="low",
)
emb = embedding_factory(
    model="text-embedding-v4",
    client=AsyncOpenAI(api_key=env_key("DASHSCOPE_API_KEY"),
                       base_url="https://dashscope.aliyuncs.com/compatible-mode/v1"),
)

ALL = {
    "faithfulness": lambda: Faithfulness(llm=llm),
    "answer_relevancy": lambda: AnswerRelevancy(llm=llm, embeddings=emb),
    "context_precision": lambda: ContextPrecision(llm=llm),
    "context_recall": lambda: ContextRecall(llm=llm),
    "context_entity_recall": lambda: ContextEntityRecall(llm=llm),
    "noise_sensitivity": lambda: NoiseSensitivity(llm=llm),
}
picked = [m.strip() for m in METRICS.split(",") if m.strip() in ALL]
M = {k: ALL[k]() for k in picked}


def val(r):
    return round(float(getattr(r, "value", r)), 4)


async def one(name, metric, row, sem):
    async with sem:
        t0 = time.time()
        try:
            if name in ("context_precision", "context_recall"):
                r = await metric.ascore(user_input=row["user_input"], reference=row["reference"],
                                        retrieved_contexts=row["retrieved_contexts"])
            elif name == "context_entity_recall":
                r = await metric.ascore(reference=row["reference"],
                                        retrieved_contexts=row["retrieved_contexts"])
            elif name == "faithfulness":
                r = await metric.ascore(user_input=row["user_input"], response=row["response"],
                                        retrieved_contexts=row["retrieved_contexts"])
            elif name == "answer_relevancy":
                r = await metric.ascore(user_input=row["user_input"], response=row["response"])
            else:  # noise_sensitivity
                r = await metric.ascore(user_input=row["user_input"], response=row["response"],
                                        reference=row["reference"],
                                        retrieved_contexts=row["retrieved_contexts"])
            return {"score": val(r), "sec": round(time.time() - t0, 1), "error": None}
        except Exception as e:
            return {"score": None, "sec": round(time.time() - t0, 1), "error": repr(e)[:300]}


def read_partial():
    out = {}
    if os.path.exists(PARTIAL):
        for line in open(PARTIAL, encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            try:
                d = json.loads(line)
                out[(d["idx"], d["metric"])] = d["result"]
            except Exception:
                pass
    return out


async def main():
    rows = json.load(open(INPUT, encoding="utf-8"))
    if LIMIT:
        rows = rows[:LIMIT]
    done = read_partial()
    todo = [(r["idx"], n, m, r) for r in rows for n, m in M.items() if (r["idx"], n) not in done]
    print(f"判官={JUDGE_MODEL}  题数={len(rows)}  指标={len(M)}  待跑={len(todo)}", flush=True)

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
    final = []
    for row in rows:
        scores = {n: allres.get((row["idx"], n), {"score": None})["score"] for n in M}
        final.append({**{k: row[k] for k in ("idx", "user_input", "response", "reference",
                                             "retrieved_contexts", "source_meta", "rag")},
                      "scores": scores})
        print(f"[{row['idx']:2d}] " + "  ".join(f"{n[:4]}={scores[n]}" for n in M), flush=True)

    json.dump(final, open(OUTPUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"\n耗时 {(time.time()-t0)/60:.1f} min -> {OUTPUT}\n\n均值（judge={JUDGE_MODEL}）：")

    import statistics as st
    for n in M:
        vs = [f["scores"][n] for f in final if f["scores"][n] is not None]
        if vs:
            print(f"  {n:22s} {st.mean(vs):.3f}  (n={len(vs)}/{len(final)})")
        else:
            print(f"  {n:22s} 无数据")


asyncio.run(main())
