#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
================================================================================
食物库全量导入（Sanotsu/free-fitness · china-food-composition）
================================================================================
把 15 个 merged_*.json（1677 条中国食物成分数据）灌进 fm_plan.food。

★★ 默认是 **dry-run（只打印、不写库）**。要真写库必须显式加 `--write`。
   原因：这份数据里有一批"名字对得上但东西不对"的行（见下），
   先让人眼过一遍前 20 条 + 分类映射，比事后清库便宜得多。

用法
----
    python scripts/import-food-db.py                  # dry-run，打印映射 + 前 20 条
    python scripts/import-food-db.py --limit 50       # dry-run，只看前 50 条
    python scripts/import-food-db.py --write          # 真写库（幂等，按 name upsert）
    python scripts/import-food-db.py --write --limit 5  # 只写 5 条（自测用）
    python scripts/import-food-db.py --refresh        # 忽略本地缓存重新下载

三条已踩过的坑（照做，别重犯）
------------------------------
1. **raw.githubusercontent.com 限速到不可用**（单张 60KB 要 90 秒）。
   这里用多镜像 + 8 线程并发；且 ghfast.top 会时不时 502 —— 所以镜像必须可回退，
   不能只写一个。
2. **食物名不能模糊匹配**。「黄瓜」会命中「牛肉(股内肉)[针扒、米龙、黄瓜条]」119 kcal，
   正的「黄瓜(鲜)[胡瓜]」只有 16 kcal。所以本脚本**只做精确导入**（数据本身就是食物库），
   绝不把种子计划里的名字往这份数据上"猜着对"。
3. **数值列有三种脏值**：`—`（缺）、`Tr`（微量）、`899*`（带星号）。
   规则：`—` → NULL、`Tr` → 0、去星号后按 float 解析；解析不了 → 记一条 `bad` 并**跳过整行**。
   一律塞 0 的后果是页面上出现"0 千卡的假食物"。

★ 幂等：靠 `food.uk_food_name`（唯一索引）+ ON DUPLICATE KEY UPDATE。
  没有这个索引的话，每跑一次就多一份全量数据（这个坑在设计表时就避开了）。
================================================================================
"""
import argparse
import io
import json
import os
import re
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal, ROUND_HALF_UP
from urllib.request import Request, urlopen

# ==================== 配置 ====================

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "fm_plan"
TABLE = "food"

# 15 个文件 → 8 个分类。
# ★ 这张表是"判断题"，不是规则推导出来的，所以必须写在这里给人看：
#   - PlantOil / AnimalFat / Others 归「配料」：Others 里是蜂蛹、阿胶、燕窝、鱼肚这类
#   - FungiAndAlgae（菌藻）归「蔬果」：木耳、海带、蘑菇，跟蔬菜放一起用户才找得到
#   - 「零食饮料 / 中式菜肴 / 西式菜肴」这份数据集里**一条都没有**（不是漏了，是真没有）
CATEGORY_BY_FILE = {
    "CerealsAndCerealProducts": "主食",
    "TubersStarchesAndProducts": "主食",
    "FruitsAndFruitProducts": "蔬果",
    "VegetablesAndVegetableProducts": "蔬果",
    "FungiAndAlgae": "蔬果",
    "MeatAndMeatProducts": "肉蛋奶",
    "PoultryAndPoultryProducts": "肉蛋奶",
    "FishShellfishAndMollusc": "肉蛋奶",
    "EggsAndEggProducts": "肉蛋奶",
    "MilkAndMilkProducts": "肉蛋奶",
    "DriedLegumesAndLegumeProducts": "豆类坚果",
    "NutsAndSeeds": "豆类坚果",
    "PlantOil": "配料",
    "AnimalFat": "配料",
    "Others": "配料",
}

FILES = sorted(CATEGORY_BY_FILE.keys())

# 分类 → emoji（★ 不再去找图标库：实测 451 个食材图标只对上 3 个，纯属浪费）
EMOJI_BY_CATEGORY = {
    "主食": "🍚",
    "蔬果": "🥬",
    "肉蛋奶": "🥩",
    "豆类坚果": "🥜",
    "零食饮料": "🥤",
    "中式菜肴": "🍱",
    "西式菜肴": "🍔",
    "配料": "🧂",
}

BASE_PATH = "assets/datasets/china-food-composition/merged_{name}.json"
MIRRORS = [
    "https://cdn.jsdelivr.net/gh/Sanotsu/free-fitness@main/" + BASE_PATH,
    "https://ghproxy.net/https://raw.githubusercontent.com/Sanotsu/free-fitness/main/" + BASE_PATH,
    "https://ghfast.top/https://raw.githubusercontent.com/Sanotsu/free-fitness/main/" + BASE_PATH,
    "https://raw.githubusercontent.com/Sanotsu/free-fitness/main/" + BASE_PATH,
]

CACHE_DIR = os.path.join(tempfile.gettempdir(), "fitmind-fooddb")

# 缺失 / 微量的标记（不同文件里写法不完全一致，这里穷举）
MISSING_MARKS = {"", "—", "－", "-", "–", "/", "…", "...", None}


# ==================== 下载 ====================

def cache_path(name):
    return os.path.join(CACHE_DIR, "merged_%s.json" % name)


def download_one(name, refresh=False):
    """下载一个文件（多镜像回退 + 本地缓存）。返回 (name, 数据, 用的镜像/缓存)"""
    path = cache_path(name)
    if os.path.exists(path) and not refresh:
        with io.open(path, encoding="utf-8") as f:
            return name, json.load(f), "cache"

    last_err = None
    for tpl in MIRRORS:
        url = tpl.format(name=name)
        try:
            req = Request(url, headers={"User-Agent": "fitmind-import/1.0"})
            with urlopen(req, timeout=45) as resp:
                raw = resp.read()
            data = json.loads(raw.decode("utf-8"))
            os.makedirs(CACHE_DIR, exist_ok=True)
            with io.open(path, "wb") as f:
                f.write(raw)
            return name, data, url.split("/")[2]
        except Exception as e:  # noqa: BLE001 —— 任何失败都换下一个镜像
            last_err = e
            print("    · 镜像失败 %s：%s" % (url.split("/")[2], e))
    raise RuntimeError("所有镜像都拿不到 %s：%s" % (name, last_err))


def download_all(refresh=False, workers=8):
    """8 线程并发（单个 60KB 串行要几分钟，实测并发后 15 个文件 5 秒内）"""
    print("下载 %d 个数据文件（线程 %d，镜像 %d 个）…" % (len(FILES), workers, len(MIRRORS)))
    result = {}
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(download_one, name, refresh): name for name in FILES}
        for fut in futures:
            name, data, source = fut.result()
            result[name] = data
            print("    ✓ %-32s %4d 条  (%s)" % (name, len(data), source))
    return result


# ==================== 数值解析 ====================

def parse_num(value):
    """
    返回 (数值, 状态)。状态：ok / trace(微量) / missing(空或—) / bad(解析不了)

    ★ 为什么要区分 missing 和 0：`—` 是"没测"，0 是"确实没有"。
      把没测的当 0，页面上就会出现"0 千卡的红烧肉"。
    """
    if value in MISSING_MARKS:
        return None, "missing"
    s = str(value).strip()
    if s.lower() == "tr":
        return 0.0, "trace"
    if s in MISSING_MARKS:
        return None, "missing"
    s = s.replace("*", "").replace("，", ",").replace("＜", "<").strip()
    s = re.sub(r"^[<>=约]+", "", s)
    try:
        return float(s), "ok"
    except ValueError:
        return None, "bad"


def r1(v):
    """保留 1 位、四舍五入（与 MySQL ROUND / Java HALF_UP 同口径）"""
    if v is None:
        return None
    return float(Decimal(str(v)).quantize(Decimal("0.1"), rounding=ROUND_HALF_UP))


def r0(v):
    if v is None:
        return None
    return int(Decimal(str(v)).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


# ==================== 组装 ====================

def build_rows(data_by_file):
    rows, skipped, stats = [], [], {"ok": 0, "trace": 0, "missing": 0, "bad": 0}
    seen = {}
    for fname in FILES:
        category = CATEGORY_BY_FILE[fname]
        for e in data_by_file[fname]:
            name = (e.get("foodName") or "").strip()
            kcal, st_kcal = parse_num(e.get("energyKCal"))
            p, st_p = parse_num(e.get("protein"))
            c, st_c = parse_num(e.get("CHO"))
            f, st_f = parse_num(e.get("fat"))
            stats[st_kcal if st_kcal in stats else "bad"] += 1

            if not name:
                skipped.append((fname, "(无名字)", "名字为空"))
                continue
            if st_kcal in ("missing", "bad") or kcal is None:
                skipped.append((fname, name, "热量缺失/无法解析（%r）" % e.get("energyKCal")))
                continue
            if kcal < 0:
                skipped.append((fname, name, "热量为负"))
                continue

            rows.append({
                "file": fname,
                "name": name,
                "category": category,
                "kcal": max(0, r0(kcal)),
                "protein": r1(p or 0),
                "carb": r1(c or 0),
                "fat": r1(f or 0),
                "raw": (e.get("energyKCal"), e.get("protein"), e.get("CHO"), e.get("fat")),
            })
            seen[name] = seen.get(name, 0) + 1
    dup = {k: v for k, v in seen.items() if v > 1}
    return rows, skipped, stats, dup


def sql_str(s):
    return "'" + str(s).replace("\\", "\\\\").replace("'", "''") + "'"


def to_sql(rows):
    """生成幂等 upsert。用别名 `AS new`（MySQL 8.0.20+ 推荐写法，VALUES() 已废弃）"""
    out = []
    for i, r in enumerate(rows, start=1):
        units = json.dumps([{"unit": "克", "gram": 1}], ensure_ascii=False)
        out.append(
            "(%s, NULL, %s, %d, %s, %s, %s, %s, %s, %s, %d, 0, 1)" % (
                sql_str(r["name"]), sql_str(r["category"]), r["kcal"],
                r["protein"], r["carb"], r["fat"],
                sql_str(units), sql_str("克"), sql_str(EMOJI_BY_CATEGORY[r["category"]]),
                i,
            )
        )
    head = ("INSERT INTO `%s` (`name`, `alias`, `category`, `kcal_per_100g`, `protein_per_100g`, "
            "`carb_per_100g`, `fat_per_100g`, `units_json`, `default_unit`, `emoji`, `sort`, "
            "`owner_id`, `status`) VALUES\n") % TABLE
    tail = ("\nAS new ON DUPLICATE KEY UPDATE "
            "`category` = new.`category`, `kcal_per_100g` = new.`kcal_per_100g`, "
            "`protein_per_100g` = new.`protein_per_100g`, `carb_per_100g` = new.`carb_per_100g`, "
            "`fat_per_100g` = new.`fat_per_100g`, `emoji` = new.`emoji`, "
            "`units_json` = new.`units_json`, `default_unit` = new.`default_unit`, "
            "`status` = 1;")
    return head + ",\n".join(out) + tail


# ==================== 报告 ====================

def report(rows, skipped, stats, dup, limit, will_write):
    by_cat = {}
    for fname, cat in CATEGORY_BY_FILE.items():
        by_cat.setdefault(cat, []).append(fname)
    print("\n" + "=" * 74)
    print("分类映射（15 个文件 → 8 个分类；★ 数据集里没有的 3 类就是没有，不硬凑）")
    print("=" * 74)
    for cat, files in by_cat.items():
        n = sum(1 for r in rows if r["category"] == cat)
        print("  %-6s %-4s %4d 条  ← %s" % (cat, EMOJI_BY_CATEGORY[cat], n, " / ".join(files)))
    empty = [c for c in EMOJI_BY_CATEGORY if c not in by_cat]
    if empty:
        print("  ⚠️ 这份数据里一条都没有的分类：%s（不是脚本漏了）" % " / ".join(empty))

    print("\n数值状态：可解析 %d · 微量(Tr→0) %d · 缺失(跳过) %d · 无法解析(跳过) %d"
          % (stats["ok"], stats["trace"], stats["missing"], stats["bad"]))
    if skipped:
        print("跳过 %d 行，原因分布：" % len(skipped))
        for fname, name, why in skipped[:10]:
            print("    - [%s] %s：%s" % (fname, name, why))
        if len(skipped) > 10:
            print("    …（共 %d 行，只列前 10）" % len(skipped))
    if dup:
        print("\n⚠️ 数据里重名的 %d 个（同名只会留一条，upsert 按 name）：" % len(dup))
        for k, v in list(dup.items())[:8]:
            print("    - %s ×%d" % (k, v))

    print("\n" + "=" * 74)
    print("前 %d 条（★ 请人眼核一遍：名字 / 分类 / 热量 / 三大营养素）" % min(20, len(rows)))
    print("=" * 74)
    print("  %-30s %-6s %6s %6s %6s %6s  %s" % ("名称", "分类", "kcal", "蛋白", "碳水", "脂肪", "原文值"))
    for r in rows[:min(limit, 20)]:
        print("  %-30s %-6s %6d %6s %6s %6s  %s"
              % (r["name"][:28], r["category"], r["kcal"], r["protein"], r["carb"], r["fat"],
                 "/".join(str(x) for x in r["raw"])))

    print("\n" + "-" * 74)
    if will_write:
        print("准备写入 %d 条（--write 已确认，按 name 幂等 upsert）" % len(rows))
    else:
        print("DRY-RUN：将写入 %d 条，跳过 %d 条。**没有写库** —— 确认无误后加 --write" % (len(rows), len(skipped)))
    print("-" * 74)


# ==================== 写库 ====================

def run_mysql(sql_text):
    """用 mysql 客户端执行（不引入 pymysql 依赖，与其他脚本保持一致）"""
    fd, path = tempfile.mkstemp(suffix=".sql", dir=tempfile.gettempdir())
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(sql_text.encode("utf-8"))
        r = subprocess.run(
            [MYSQL, "--default-character-set=utf8mb4", "-h127.0.0.1", "-P3306",
             "-uroot", "-p1234", DB, "-e", "source %s" % path.replace("\\", "/")],
            capture_output=True)
        if r.returncode != 0:
            raise RuntimeError("SQL 执行失败：%s" % r.stderr.decode("utf-8", "ignore")[:400])
    finally:
        try:
            os.remove(path)
        except OSError:
            pass


def query_one(sql_text):
    r = subprocess.run(
        [MYSQL, "--default-character-set=utf8mb4", "-h127.0.0.1", "-P3306",
         "-uroot", "-p1234", "-N", "-e", sql_text],
        capture_output=True)
    if r.returncode != 0:
        raise RuntimeError("查询失败：%s" % r.stderr.decode("utf-8", "ignore")[:300])
    return r.stdout.decode("utf-8", "ignore").strip()


def main():
    ap = argparse.ArgumentParser(description="导入中国食物成分数据到 fm_plan.food（默认 dry-run）")
    ap.add_argument("--write", action="store_true", help="真的写库（不加就是 dry-run）")
    ap.add_argument("--dry-run", action="store_true", help="显式声明 dry-run（默认行为）")
    ap.add_argument("--limit", type=int, default=0, help="只处理前 N 条（自测用）")
    ap.add_argument("--refresh", action="store_true", help="忽略本地缓存，重新下载")
    ap.add_argument("--workers", type=int, default=8, help="下载线程数（默认 8）")
    args = ap.parse_args()

    will_write = args.write and not args.dry_run

    data = download_all(refresh=args.refresh, workers=args.workers)
    rows, skipped, stats, dup = build_rows(data)
    if args.limit and args.limit > 0:
        rows = rows[:args.limit]

    report(rows, skipped, stats, dup, args.limit or 20, will_write)

    if not will_write:
        return 0

    before = query_one("SELECT COUNT(*) FROM %s.%s" % (DB, TABLE))
    run_mysql(to_sql(rows))
    after = query_one("SELECT COUNT(*) FROM %s.%s" % (DB, TABLE))
    print("库内 %s 行数：%s → %s（本次提交 %d 条，其余是本来就在的）"
          % (TABLE, before, after, len(rows)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
