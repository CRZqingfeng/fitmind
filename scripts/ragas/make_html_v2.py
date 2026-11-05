# -*- coding: utf-8 -*-
"""把两轮六指标 RAGAS 结果生成单文件 HTML 报告（无外部依赖，双击可开）。

数据来源：.tmp/ragas/glm_n1.json / glm_n2.json（eval_glm.py 的产物）
"""
import json, statistics as st, html, sys, os

META = [
    ("faithfulness", "忠实度", 0.90, "high", "回答里的每句结论，注入的资料是否支撑得住"),
    ("answer_relevancy", "回答相关性", 0.88, "high", "回答是否真的在回答问题（反向生成问题再算相似度）"),
    ("context_precision", "上下文精度", 0.85, "high", "注入的块里，有用的排得靠不靠前"),
    ("context_recall", "上下文召回", 0.70, "high", "回答需要的信息，有没有被捞回来"),
    ("context_entity_recall", "实体召回", 0.70, "high", "参考答案里的关键实体，在上下文里覆盖率（★ 中文语料下该指标不可靠）"),
    ("noise_sensitivity", "噪声敏感度", 0.10, "low", "回答里无依据的主张，有多少能追到无关上下文（越低越好）"),
]

_arg = lambda k, d: next((a.split("=", 1)[1] for a in sys.argv if a.startswith(f"--{k}=")), d)
# ★ 换项目时用 --r1=/--r2= 指定两轮结果，--out= 指定输出
_r1 = _arg("r1", ".tmp/ragas/glm_n1.json")
_r2 = _arg("r2", ".tmp/ragas/glm_n2.json")
ROUNDS = [("第一轮", _r1), ("第二轮", _r2)]
OUT = _arg("out", "docs/RAGAS六指标评估.html")


def load(path):
    if not os.path.exists(path):
        return None
    return json.load(open(path, encoding="utf-8"))


data = [(name, load(p)) for name, p in ROUNDS]
data = [(n, d) for n, d in data if d]
if not data:
    print("没有数据"); sys.exit(1)

n_q = len(data[0][1])
print(f"轮次 {len(data)}，每轮 {n_q} 题")


def clean(v):
    return [x for x in v if x is not None]


summary = {}
for key, label, thr, direction, desc in META:
    rounds_means, all_vals, missing = [], [], 0
    for _, rows in data:
        vs = clean([r["scores"].get(key) for r in rows])
        missing += len(rows) - len(vs)
        all_vals += vs
        rounds_means.append(round(st.mean(vs), 4) if vs else None)
    ok = sum(1 for v in all_vals if (v >= thr if direction == "high" else v <= thr))
    summary[key] = {
        "label": label, "thr": thr, "dir": direction, "desc": desc,
        "mean": round(st.mean(all_vals), 4) if all_vals else None,
        "rounds": rounds_means,
        "min": round(min(all_vals), 4) if all_vals else None,
        "max": round(max(all_vals), 4) if all_vals else None,
        "pass": ok, "total": len(all_vals), "missing": missing,
    }

# 逐题：两轮平均
per_q = []
for i in range(n_q):
    base = data[0][1][i]
    rec = {"idx": base["idx"], "q": base["user_input"], "scores": {},
           "rounds": {}, "response": base["response"], "reference": base["reference"],
           "contexts": base["retrieved_contexts"]}
    for key, *_ in META:
        vals = [r["scores"].get(key) for _, rows in data
                for r in rows if r["idx"] == base["idx"] and r["scores"].get(key) is not None]
        rec["rounds"][key] = [round(v, 3) for v in vals]
        rec["scores"][key] = round(st.mean(vals), 4) if vals else None
    per_q.append(rec)

payload = {"n": n_q, "rounds": [n for n, _ in data], "metrics": summary,
           "meta": [{"key": k, "label": l, "thr": t, "dir": d, "desc": ds} for k, l, t, d, ds in META],
           "rows": per_q}

passed = sum(1 for k, v in summary.items() if v["mean"] is not None and
             ((v["mean"] >= v["thr"]) if v["dir"] == "high" else (v["mean"] <= v["thr"])))


def esc(s):
    return html.escape(str(s or ""))


rows_html = []
for r in per_q:
    cells = []
    for key, label, thr, direction, _ in META:
        v = r["scores"][key]
        if v is None:
            cells.append('<td class="na">—</td>')
            continue
        ok = (v >= thr) if direction == "high" else (v <= thr)
        gap = abs(v - thr)
        cls = "good" if ok else ("near" if gap <= 0.08 else "bad")
        cells.append(f'<td class="sc {cls}">{v:.2f}</td>')
    detail = (
        f'<div class="detail"><div class="dcol">'
        f'<h4>模型回答</h4><p>{esc(r["response"])}</p>'
        f'<h4>参考答案</h4><p class="dim">{esc(r["reference"])}</p></div>'
        f'<div class="dcol"><h4>注入的上下文（{len(r["contexts"])} 条）</h4>'
        + "".join(f'<p class="ctx">{esc(c[:220])}{"…" if len(c) > 220 else ""}</p>' for c in r["contexts"])
        + "</div></div>"
    )
    rows_html.append(
        f'<tr class="qrow" data-i="{r["idx"]}">'
        f'<td class="idx">{r["idx"]}</td><td class="q">{esc(r["q"])}</td>'
        + "".join(cells) +
        f'<td class="exp"><span class="chev">▸</span></td></tr>'
        f'<tr class="drow" data-i="{r["idx"]}"><td colspan="{len(META) + 3}">{detail}</td></tr>'
    )

header_cells = "".join(f'<th title="{esc(d)}">{esc(l)}<br><span class="th-thr">{"≥" if dr == "high" else "≤"}{t}</span></th>'
                       for _, l, t, dr, d in META)

bar_rows = []
for key, label, thr, direction, _ in META:
    m = summary[key]
    if m["mean"] is None:
        continue
    # ★ 统一用 0~1 刻度：条长 = 分数本身，竖线 = 目标。
    #   不要把"越低越好"的指标（噪声）按达标比例画满 —— 那样看着像"满分"，
    #   方向完全反了（第一版就是这么错的）。
    pct = max(1.5, min(100.0, m["mean"] * 100))
    tgt = min(99.0, thr * 100)
    ok = (m["mean"] >= thr) if direction == "high" else (m["mean"] <= thr)
    bar_rows.append(f'''
    <div class="bar-row">
      <div class="bar-label">{esc(label)}</div>
      <div class="bar-track">
        <div class="bar-fill {'ok' if ok else 'no'}" style="width:{pct:.1f}%"></div>
        <div class="bar-target" style="left:{tgt:.1f}%"></div>
      </div>
      <div class="bar-val">{m['mean']:.3f}<span class="bar-thr"> / {'≥' if direction=='high' else '≤'}{thr}</span></div>
      <div class="bar-flag {'ok' if ok else 'no'}">{'✓ 达标' if ok else '✗ 未达'}</div>
    </div>''')

def fmt_delta(vals):
    """两轮差：≤0.03 视为稳定。"""
    if len(vals) < 2 or None in vals:
        return "—", ""
    d = abs(vals[0] - vals[-1])
    return f"{d:.3f}", ("good" if d <= 0.03 else "near")


_rounds = []
for _k, _l, _t, _d, _ in META:
    _m = summary[_k]
    _tds = "".join(f'<td>{("—" if v is None else f"{v:.3f}")}</td>' for v in _m["rounds"])
    _dtxt, _dcls = fmt_delta(_m["rounds"])
    _rounds.append(f'<tr><td>{esc(_m["label"])}</td>{_tds}'
                   f'<td class="{_dcls}">{_dtxt}</td><td>{_m["pass"]}/{_m["total"]}</td></tr>')
round_rows = "".join(_rounds)

HTML = f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>FitMind RAG 六指标评估（2026-11-05）</title>
<style>
:root{{--bg:#0f1115;--card:#171a21;--card2:#1d2129;--line:#2a2f3a;--txt:#e6e8ee;--dim:#98a0b3;
--ok:#3ddc97;--warn:#ffb454;--bad:#ff6b6b;--acc:#5b9cff}}
*{{box-sizing:border-box}}
body{{margin:0;background:var(--bg);color:var(--txt);font:15px/1.7 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif}}
.wrap{{max-width:1180px;margin:0 auto;padding:36px 22px 80px}}
h1{{font-size:26px;margin:0 0 6px;letter-spacing:.3px}}
.sub{{color:var(--dim);font-size:13px;margin-bottom:26px}}
h2{{font-size:18px;margin:38px 0 14px;padding-left:10px;border-left:3px solid var(--acc)}}
.card{{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:18px 20px}}
.kpis{{display:grid;grid-template-columns:repeat(auto-fit,minmax(178px,1fr));gap:12px}}
.kpi{{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px 16px}}
.kpi .k{{font-size:12px;color:var(--dim)}}
.kpi .v{{font-size:26px;font-weight:700;margin:4px 0 2px;font-variant-numeric:tabular-nums}}
.kpi .t{{font-size:11px;color:var(--dim)}}
.kpi.ok .v{{color:var(--ok)}} .kpi.no .v{{color:var(--bad)}} .kpi.odd .v{{color:var(--warn)}}
.badge{{display:inline-block;font-size:11px;padding:1px 7px;border-radius:99px;margin-left:6px;vertical-align:2px}}
.badge.ok{{background:rgba(61,220,151,.14);color:var(--ok)}}
.badge.no{{background:rgba(255,107,107,.14);color:var(--bad)}}
.bar-row{{display:grid;grid-template-columns:96px 1fr 132px 52px;align-items:center;gap:12px;margin:11px 0}}
.bar-label{{font-size:13px;color:var(--dim);text-align:right}}
.bar-track{{position:relative;height:16px;background:var(--card2);border-radius:8px;overflow:hidden}}
.bar-fill{{height:100%;border-radius:8px}}
.bar-fill.ok{{background:linear-gradient(90deg,#2f8f6b,#3ddc97)}}
.bar-fill.no{{background:linear-gradient(90deg,#8f4141,#ff6b6b)}}
.bar-target{{position:absolute;top:-3px;width:2px;height:22px;background:rgba(255,255,255,.55)}}
.bar-val{{font-variant-numeric:tabular-nums;font-size:14px;text-align:right}}
.bar-thr{{color:var(--dim);font-size:11px}}
.bar-flag{{font-size:12px;text-align:center}}
.bar-flag.ok{{color:var(--ok)}} .bar-flag.no{{color:var(--bad)}}
table{{width:100%;border-collapse:collapse;font-size:13px}}
th,td{{padding:8px 10px;border-bottom:1px solid var(--line);text-align:left}}
th{{color:var(--dim);font-weight:600;font-size:12px;white-space:nowrap}}
.th-thr{{color:#5d6474;font-weight:400}}
td.idx{{color:var(--dim);width:38px}}
td.sc{{text-align:center;font-variant-numeric:tabular-nums;width:74px;font-weight:600}}
.sc.good{{color:var(--ok)}} .sc.near{{color:var(--warn)}} .sc.bad{{color:var(--bad)}} .na{{color:#4a5060;text-align:center}}
.qrow{{cursor:pointer}} .qrow:hover{{background:var(--card2)}}
td.exp{{width:26px;color:var(--dim);text-align:center}}
.drow{{display:none}} .drow.open{{display:table-row}}
.detail{{display:grid;grid-template-columns:1fr 1fr;gap:20px;padding:10px 4px 16px;background:#12151b}}
.dcol h4{{margin:8px 0 4px;font-size:12px;color:var(--acc)}}
.detail p{{margin:0 0 8px;font-size:13px;color:var(--txt);white-space:pre-wrap}}
.detail p.dim{{color:var(--dim)}}
.detail p.ctx{{background:var(--card2);border-radius:8px;padding:8px 10px;font-size:12px;color:#c3c9d6}}
.note{{background:rgba(255,180,84,.08);border:1px solid rgba(255,180,84,.25);border-radius:10px;padding:14px 18px;font-size:13px;color:#e8d3b0;margin-top:14px}}
.note b{{color:var(--warn)}}
footer{{margin-top:44px;color:var(--dim);font-size:12px;border-top:1px solid var(--line);padding-top:16px}}
code{{background:var(--card2);padding:1px 6px;border-radius:4px;font-size:12px}}
</style></head><body><div class="wrap">

<h1>FitMind · RAG 六指标评估</h1>
<div class="sub">2026-11-05 ｜ {len(data)} 轮 × {n_q} 题 ｜ 判官 <b>GLM-5.3-Flash</b> ｜ 生成 <b>deepseek-flash</b> ｜ 索引 128 块（已消碎片）</div>

<div class="kpis">
{"".join(f'''<div class="kpi {'ok' if m['mean'] is not None and ((m['mean']>=m['thr']) if m['dir']=='high' else (m['mean']<=m['thr'])) else 'no'}">
  <div class="k">{esc(m['label'])}</div>
  <div class="v">{'—' if m['mean'] is None else f"{m['mean']:.3f}"}</div>
  <div class="t">目标 {'≥' if m['dir']=='high' else '≤'}{m['thr']} · {' / '.join('—' if v is None else f'{v:.3f}' for v in m['rounds'])}</div>
</div>''' for m in summary.values())}
</div>

<div class="note">
<b>先看这句：</b>六项里 <b>{passed}/6</b> 项按目标达标（逐轮均值判定）。四项核心指标（忠实度 / 相关性 / 精度 / 召回）是面试要能答的部分；
<b>实体召回</b>在中文语料下会假 0（判官抽出的实体形态与语料不符，实测关键实体字面存在也给 0），<b>噪声敏感度</b>对判官口径极敏感，这两项只看趋势、不当硬门槛。
</div>

<h2>一、各项指标 vs 目标</h2>
<div class="card">{"".join(bar_rows)}
<div class="sub" style="margin:10px 0 0;font-size:12px">竖线 = 目标值。绿色达标、红色未达。</div></div>

<h2>二、两轮一致性（判断这次分数能不能信）</h2>
<div class="card"><table>
<thead><tr><th>指标</th>{"".join(f"<th>{esc(n)}</th>" for n, _ in data)}<th>两轮差</th><th>达标题数</th></tr></thead>
<tbody>{round_rows}</tbody></table>
<div class="sub" style="margin:10px 0 0;font-size:12px">两轮差 ≤0.03 视为稳定（绿色）；差得越远，说明单次采样越不可信 —— 这也是为什么这次跑两轮。</div>
</div>

<h2>三、逐题明细（点任意一行展开回答与上下文）</h2>
<div class="card"><table>
<thead><tr><th>#</th><th>问题</th>{header_cells}<th></th></tr></thead>
<tbody>{"".join(rows_html)}</tbody></table></div>

<footer>
<b>口径</b>：被测系统为 fm-aigc 真实服务（Spring Boot，直连 18094，跳过网关与 Nacos），
检索走 ES + 百炼 text-embedding-v4（1024 维），混合检索 RRF + gte-rerank-v2 重排，topK=6；
回答由 deepseek-flash 生成，判官为 glm-5.3-flash（另一家族，避免自评偏差）。<br>
<b>成本</b>：生成 {len(data) * n_q} 次 ≈ 0.21 元；判官 0 元（智谱赠送额度）。<br>
<b>局限</b>：① 27 题与语料同源，测的是"链路是否正常"，不代表真实提问难度；② 单题分数受判官采样影响，
两轮之间的差值见第二节；③ 实体召回与噪声敏感度的口径问题已在页首说明。
</footer>
</div>
<script>
const D = {json.dumps(payload, ensure_ascii=False)};
document.querySelectorAll('.qrow').forEach(function (tr) {{
  tr.addEventListener('click', function () {{
    var i = tr.dataset.i;
    var d = document.querySelector('.drow[data-i="' + i + '"]');
    d.classList.toggle('open');
    var c = tr.querySelector('.chev');
    if (c) c.textContent = d.classList.contains('open') ? '▾' : '▸';
  }});
}});
</script></body></html>"""

os.makedirs("docs", exist_ok=True)
open(OUT, "w", encoding="utf-8").write(HTML)
print(f"已生成 {OUT}  ({len(HTML)} 字符)")
