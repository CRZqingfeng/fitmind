#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
================================================================================
动作库全量导入（Sanotsu/free-fitness · free-exercise-db-zh）
================================================================================
把 873 条中文动作数据灌进 fm_plan.exercise。

★★ 默认是 **dry-run（只打印、不写库）**。要真写库必须显式加 `--write`。
   理由同 import-food-db.py：数据源里有一批"名字对但东西不对"的行
   （比如 `dips bench` 这种全小写英文名），先让人眼过一遍前 20 条 + 三张映射分布，
   比事后清库便宜得多。

用法
----
    python scripts/import-exercise-db.py                  # dry-run：映射统计 + 前 20 条 + 分布 + 未知值检查
    python scripts/import-exercise-db.py --limit 20       # 只看 20 条
    python scripts/import-exercise-db.py --write          # 真写库（幂等，按 name upsert）
    python scripts/import-exercise-db.py --refresh        # 忽略本地缓存重新下载

四条已踩过的坑（照做，别重犯）
------------------------------
1. **raw.githubusercontent.com 限速到不可用 / jsdelivr 在本机 TLS 握手失败**。
   所以镜像表留 4 个、逐个回退，且**顺序是实测出来的**（本机 ghfast 可用），
   不是拍脑袋排的。
2. **3 条名字带首尾空格**（`' dips bench'` / `' dips机'` / `' Pallof 挤压'`）。
   不 strip 会绕过 `uk_exercise_name` 造出重复行（唯一索引不认"空格差异"）。
3. **`instructions` 里有引号和反斜杠**（最长 763 字符）→ 只能 `json.dumps`，
   手工拼字符串会产出非法 JSON，`JSON_VALID` 直接假。
4. **`equipment` 有 77 条为 `null`** → 显式映射到"其他"。
   注意 `None` 是一个**合法的键**，不能当成"没配"。

★ 幂等：靠 `exercise.uk_exercise_name`（name）+ ON DUPLICATE KEY UPDATE。
★ **只更新"数据集说了算"的列**，绝不更新：
    · `id`             —— 官方计划 / 用户计划 / workout_set_log 全指着它
    · `image_url`      —— 由 import-exercise-images.py 单独负责（各改各的列）
    · `heat` / `lumbar_pressure` / `gif_url` / `tips` —— 数据集没有，本来就 NULL
================================================================================
"""
import argparse
import io
import json
import os
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor
from urllib.request import Request, urlopen

# ==================== 配置 ====================

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "fm_plan"
TABLE = "exercise"

BASE_PATH = "assets/datasets/free-exercise-db-zh.json"

# ★ 镜像顺序 = 2026-11-01 本机实测结果，不是拍脑袋：
#     ghfast.top              → 200，单张 1~3s（12 张抽样全 200）
#     ghproxy.net             → 502
#     cdn.jsdelivr.net        → TLS 握手失败（schannel: failed to receive handshake）
#     raw.githubusercontent   → 限速到不可用
#   换机器时顺序可能不同，所以四个都留着、逐个回退。
MIRRORS = [
    "https://ghfast.top/https://raw.githubusercontent.com/Sanotsu/free-fitness/main/",
    "https://ghproxy.net/https://raw.githubusercontent.com/Sanotsu/free-fitness/main/",
    "https://cdn.jsdelivr.net/gh/Sanotsu/free-fitness@main/",
    "https://raw.githubusercontent.com/Sanotsu/free-fitness/main/",
]

CACHE_DIR = os.path.join(tempfile.gettempdir(), "fitmind-exercise-db")

# ==================== 三张映射表（本期的核心判断题） ====================
#
# ★★ 这三张表是**人工判断的结果，不是规则推导出来的**，所以必须写死在代码里并写清理由。
#    下次有人想"优化分类"，得先看见当初为什么这么分。
# ★★ 出现表里没有的英文值 → **报错退出**，不许默默归"其他"：
#    数据源哪天加了新 category，默默归"其他"会让"力量 736"这个数悄悄变化，
#    而验收脚本断言的正是分布。

# --- ① category：7 个英文值 → 3 个中文值（数据集没有"热身"，不补） ---
CATEGORY_MAP = {
    "strength": "力量",
    "powerlifting": "力量",           # 力量举（深蹲/卧推/硬拉）：力量训练的子类
    "olympic weightlifting": "力量",  # 奥举（抓举/挺举）：同上
    "strongman": "力量",              # 大力士（农夫行走/翻轮胎）：同上
    # ★ 判断题：plyometrics（弹跳/爆发）归"力量"。
    #   它是**短时无氧**，不是持续供能；归"有氧"会让有氧里全是跳箱，
    #   而真正的心肺训练只有 14 条。
    "plyometrics": "力量",
    "stretching": "拉伸",
    "cardio": "有氧",
    # ★★ 没有 "warmup"/"热身" 这个值 —— 数据集真没有，不编 20 条"原地踏步"凑数。
    #    后果是字典里"热身"这一项会消失（这是正解，不是 bug）。
}

# --- ② body_part：17 个英文肌群 → 12 个中文部位（11 个 + "其他"） ---
BODY_PART_MAP = {
    "chest": "胸",
    "lats": "背",
    "middle back": "背",
    "lower back": "背",
    "quadriceps": "腿",
    "hamstrings": "腿",
    "adductors": "腿",
    "shoulders": "肩",
    "biceps": "二头",
    "triceps": "三头",
    "calves": "小腿",
    "forearms": "前臂",
    "glutes": "臀部",
    "abductors": "臀部",
    "abdominals": "腹部",
    "traps": "斜方肌",
    # ★ neck 只有 8 条，11 个部位常量里无处安放 → "其他"。
    #   exercise 表的列注释本来就写了"其他"这个值（是 PlanConstants 漏了它）。
    #   字典动态化后"其他"会自动出现，这 8 条不会变孤儿。
    "neck": "其他",
}

# --- ③ equipment：13 个英文值 → 8 个中文值 ---
EQUIPMENT_MAP = {
    "body only": "徒手",
    "dumbbell": "哑铃",
    "barbell": "杠铃",
    "e-z curl bar": "杠铃",     # 曲杆是杠铃的一种
    "cable": "固定器械",         # 龙门架 / 绳索机
    "machine": "固定器械",       # 腿推机 / 蝴蝶机
    "bands": "弹力带",
    # ★ 判断题：这 6 类合并成"其他"，代价是"其他"占 292/873（1/3）。
    #   可接受 —— 比编 6 个新枚举再改前端筛选便宜得多。
    "kettlebells": "其他",
    "medicine ball": "其他",
    "exercise ball": "其他",
    "foam roll": "其他",
    "other": "其他",
    None: "其他",               # ★ 77 条 equipment 为 null；None 是合法键，不是"没配"
    # ★★ 没有 "smith" / "hammer strength" —— 数据集根本没有这两类机器。
    #    旧文档说"P3 全量导入时按子类补"已被实测推翻：补不了，也不许编。
    #    正解是字典动态化，让这两个空枚举自己消失。
}

# --- 难度：与 P0 种子一致 ---
DIFFICULTY_MAP = {"beginner": 1, "intermediate": 3, "expert": 5}

# --- 参照分布（只用来打印"实际 vs 预期"，飘了给 ⚠️，不阻断；阻断的只有"未知值"） ---
EXPECT_CATEGORY = {"力量": 736, "拉伸": 123, "有氧": 14}
EXPECT_BODY_PART = {
    "胸": 84, "背": 99, "腿": 240, "肩": 127, "二头": 53, "三头": 71,
    "小腿": 28, "前臂": 25, "臀部": 30, "腹部": 93, "斜方肌": 15, "其他": 8,
}
EXPECT_EQUIPMENT = {
    "徒手": 111, "哑铃": 123, "杠铃": 179, "固定器械": 148, "弹力带": 20, "其他": 292,
}
EXPECT_TOTAL = 873


# ==================== 下载 ====================

def cache_path():
    return os.path.join(CACHE_DIR, "free-exercise-db-zh.json")


def download(refresh=False):
    """下载数据集（多镜像回退 + 本地缓存）。返回 (数据, 来源说明)"""
    path = cache_path()
    if os.path.exists(path) and not refresh:
        with io.open(path, encoding="utf-8") as f:
            return json.load(f), "cache"

    last_err = None
    for tpl in MIRRORS:
        url = tpl + BASE_PATH
        try:
            req = Request(url, headers={"User-Agent": "fitmind-import/1.0"})
            with urlopen(req, timeout=60) as resp:
                raw = resp.read()
            data = json.loads(raw.decode("utf-8"))
            os.makedirs(CACHE_DIR, exist_ok=True)
            with io.open(path, "wb") as f:
                f.write(raw)
            return data, "%s (%d 字节)" % (url.split("/")[2], len(raw))
        except Exception as e:  # noqa: BLE001 —— 任何失败都换下一个镜像
            last_err = e
            print("    · 镜像失败 %s：%s" % (url.split("/")[2], e))
    raise RuntimeError("所有镜像都拿不到 %s：%s" % (BASE_PATH, last_err))


# ==================== 组装 ====================

def primary_muscle_of(record):
    """数据集里 primaryMuscles **全是单值**（实测 873 条逐条确认）。

    ★ 不取 [0] 就静默截断的话，将来数据源变成多值时我们会**悄悄丢掉后几个肌群**，
      而分组结果看起来"还是对的"。所以这里显式要求"恰好 1 个"。
    """
    muscles = record.get("primaryMuscles") or []
    if len(muscles) != 1:
        return None
    return muscles[0]


def build_rows(data):
    rows, unknown, skipped = [], {"category": {}, "bodyPart": {}, "equipment": {}, "level": {}}, []

    for e in data:
        raw_name = e.get("name") or ""
        name = raw_name.strip()
        if not name:
            skipped.append(("(无名字)", "名字为空"))
            continue

        cat_en = e.get("category")
        if cat_en not in CATEGORY_MAP:
            unknown["category"][str(cat_en)] = unknown["category"].get(str(cat_en), 0) + 1
            continue

        muscle_en = primary_muscle_of(e)
        if muscle_en is None:
            unknown["bodyPart"]["(主肌群数量≠1：%r)" % (e.get("primaryMuscles"),)] = \
                unknown["bodyPart"].get("(主肌群数量≠1)", 0) + 1
            continue
        if muscle_en not in BODY_PART_MAP:
            unknown["bodyPart"][str(muscle_en)] = unknown["bodyPart"].get(str(muscle_en), 0) + 1
            continue

        equip_en = e.get("equipment")
        if equip_en not in EQUIPMENT_MAP:
            unknown["equipment"][str(equip_en)] = unknown["equipment"].get(str(equip_en), 0) + 1
            continue

        level_en = e.get("level")
        if level_en not in DIFFICULTY_MAP:
            unknown["level"][str(level_en)] = unknown["level"].get(str(level_en), 0) + 1
            continue

        steps = e.get("instructions") or []
        rows.append({
            "name": name,
            "raw_name": raw_name,
            "en_name": e.get("id"),
            "category": CATEGORY_MAP[cat_en],
            "body_part": BODY_PART_MAP[muscle_en],
            "equipment": EQUIPMENT_MAP[equip_en],
            "difficulty": DIFFICULTY_MAP[level_en],
            "primary_json": json.dumps(e.get("primaryMuscles") or [], ensure_ascii=False),
            "secondary_json": json.dumps(e.get("secondaryMuscles") or [], ensure_ascii=False),
            "steps_json": json.dumps(steps, ensure_ascii=False),
            "empty_steps": len(steps) == 0,
            "trimmed": raw_name != name,
        })
    return rows, unknown, skipped


def sql_str(s):
    if s is None:
        return "NULL"
    return "'" + str(s).replace("\\", "\\\\").replace("'", "''") + "'"


def to_sql(rows):
    """幂等 upsert。用别名 `AS new`（MySQL 8.0.20+ 推荐写法，VALUES() 已废弃）"""
    out = []
    for r in rows:
        out.append("(%s, %s, %s, %s, %s, %d, %s, %s, %s, 1)" % (
            sql_str(r["name"]), sql_str(r["en_name"]), sql_str(r["category"]),
            sql_str(r["body_part"]), sql_str(r["equipment"]), r["difficulty"],
            sql_str(r["primary_json"]), sql_str(r["secondary_json"]), sql_str(r["steps_json"]),
        ))
    head = ("INSERT INTO `%s` (`name`, `en_name`, `category`, `body_part`, `equipment`, "
            "`difficulty`, `primary_muscles_json`, `secondary_muscles_json`, `steps_json`, "
            "`status`) VALUES\n") % TABLE
    # ★ 只更新"数据集说了算"的列。刻意不写：
    #   id（历史记录靠它）/ image_url（图片脚本负责）/ heat·lumbar_pressure·gif_url·tips（本来就 NULL）
    tail = ("\nAS new ON DUPLICATE KEY UPDATE "
            "`en_name` = new.`en_name`, `category` = new.`category`, "
            "`body_part` = new.`body_part`, `equipment` = new.`equipment`, "
            "`difficulty` = new.`difficulty`, "
            "`primary_muscles_json` = new.`primary_muscles_json`, "
            "`secondary_muscles_json` = new.`secondary_muscles_json`, "
            "`steps_json` = new.`steps_json`, `status` = 1;")
    return head + ",\n".join(out) + tail


# ==================== 报告 ====================

def dist(rows, key):
    out = {}
    for r in rows:
        out[r[key]] = out.get(r[key], 0) + 1
    return out


def print_dist(title, actual, expect, order):
    print("\n" + "-" * 74)
    print(title)
    print("-" * 74)
    keys = [k for k in order if k in actual] + [k for k in actual if k not in order]
    bad = False
    for k in keys:
        n = actual[k]
        exp = expect.get(k)
        if exp is None:
            mark = "⚠️ 预期里没有这一项"
            bad = True
        elif exp != n:
            mark = "⚠️ 预期 %d" % exp
            bad = True
        else:
            mark = "✓"
        print("  %-8s %4d  %s" % (k, n, mark))
    for k, exp in expect.items():
        if k not in actual:
            print("  %-8s %4d  ⚠️ **实际一条都没有**" % (k, exp))
            bad = True
    print("  合计 %d（预期 %d）%s" % (sum(actual.values()), sum(expect.values()),
                                  "" if sum(actual.values()) == sum(expect.values()) else " ⚠️ 对不上"))
    return not bad


def report(rows, unknown, skipped, limit, will_write):
    print("\n" + "=" * 74)
    print("条数：数据集 %d 条 → 可写入 %d · 跳过 %d" % (len(rows) + len(skipped), len(rows), len(skipped)))
    print("=" * 74)
    if skipped:
        for name, why in skipped[:10]:
            print("    - %s：%s" % (name, why))

    total_unknown = sum(len(v) for v in unknown.values())
    if total_unknown:
        print("\n❌ **三张映射表没覆盖到的英文值（必须补表，不许默默归\"其他\"）**：")
        for dim, kv in unknown.items():
            for k, n in kv.items():
                print("    [%s] %s ×%d" % (dim, k, n))
        print("  → 修法：把上面这些值补进 CATEGORY_MAP / BODY_PART_MAP / EQUIPMENT_MAP / DIFFICULTY_MAP，")
        print("     并写清\"为什么归这一类\"。**不要**改成 if-else 猜。")

    ok_c = print_dist("category 映射后分布", dist(rows, "category"), EXPECT_CATEGORY,
                      ["力量", "拉伸", "有氧", "热身"])
    ok_b = print_dist("body_part 映射后分布", dist(rows, "body_part"), EXPECT_BODY_PART,
                      ["胸", "背", "腿", "肩", "二头", "三头", "小腿", "前臂", "臀部", "腹部", "斜方肌", "其他"])
    ok_e = print_dist("equipment 映射后分布", dist(rows, "equipment"), EXPECT_EQUIPMENT,
                      ["徒手", "哑铃", "杠铃", "固定器械", "弹力带", "其他"])

    trimmed = [r["raw_name"] for r in rows if r["trimmed"]]
    empty_steps = [r["name"] for r in rows if r["empty_steps"]]
    print("\n" + "-" * 74)
    print("脏值处理：名字带首尾空格 %d 条（已 strip）%s" % (len(trimmed), trimmed))
    print("          instructions 为空 %d 条（如实存 '[]'，不补假步骤）：%s" % (len(empty_steps), empty_steps))
    print("          最长 name %d / en_name %d / secondary_json %d / steps_json %d"
          % (max(len(r["name"]) for r in rows),
             max(len(r["en_name"] or "") for r in rows),
             max(len(r["secondary_json"]) for r in rows),
             max(len(r["steps_json"]) for r in rows)))

    print("\n" + "=" * 74)
    print("前 %d 条（★ 请人眼核一遍：名字 / 分类 / 部位 / 器械 / 难度）" % min(20, len(rows)))
    print("=" * 74)
    print("  %-30s %-6s %-6s %-8s %-4s %s" % ("名称", "分类", "部位", "器械", "难度", "英文 id"))
    for r in rows[:min(limit, 20)]:
        print("  %-30s %-6s %-6s %-8s %-4d %s" % (
            r["name"][:28], r["category"], r["body_part"], r["equipment"], r["difficulty"],
            (r["en_name"] or "")[:40]))

    print("\n" + "-" * 74)
    if will_write:
        print("准备写入 %d 条（--write 已确认，按 name 幂等 upsert）" % len(rows))
    else:
        print("DRY-RUN：将写入 %d 条，跳过 %d 条。**没有写库** —— 确认无误后加 --write"
              % (len(rows), len(skipped)))
    print("-" * 74)
    return total_unknown == 0, ok_c and ok_b and ok_e


# ==================== 写库 ====================

def run_mysql_file(sql_text):
    """用 mysql 客户端执行（不引入 pymysql：中文列名/字符集踩过坑）"""
    fd, path = tempfile.mkstemp(suffix=".sql", dir=tempfile.gettempdir())
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(sql_text.encode("utf-8"))
        r = subprocess.run(
            [MYSQL, "--default-character-set=utf8mb4", "-h127.0.0.1", "-P3306",
             "-uroot", "-p1234", DB, "-e", "source %s" % path.replace("\\", "/")],
            capture_output=True)
        if r.returncode != 0:
            raise RuntimeError("SQL 执行失败：%s" % r.stderr.decode("utf-8", "ignore")[:500])
    finally:
        try:
            os.remove(path)
        except OSError:
            pass


def query(sql_text):
    r = subprocess.run(
        [MYSQL, "--default-character-set=utf8mb4", "-h127.0.0.1", "-P3306",
         "-uroot", "-p1234", "-N", "-e", sql_text],
        capture_output=True)
    if r.returncode != 0:
        raise RuntimeError("查询失败：%s" % r.stderr.decode("utf-8", "ignore")[:300])
    return r.stdout.decode("utf-8", "ignore").strip()


def reread():
    """★ 写完之后**必须复读一次库里的分布** —— "我认为写进去了" ≠ "库里是那样"。"""
    print("\n" + "=" * 74)
    print("复读：库里现在的真实分布")
    print("=" * 74)
    print("  总行数 %s" % query("SELECT COUNT(*) FROM %s.%s" % (DB, TABLE)))
    for key in ("category", "body_part", "equipment"):
        out = query("SELECT CONCAT(%s,'=',COUNT(*)) FROM %s.%s GROUP BY %s ORDER BY COUNT(*) DESC"
                    % (key, DB, TABLE, key))
        print("  %-10s %s" % (key, " · ".join(out.split("\n")) if out else "(空)"))
    print("  steps_json 非 JSON 的行 %s（必须是 0）"
          % query("SELECT COUNT(*) FROM %s.%s WHERE steps_json IS NOT NULL AND JSON_VALID(steps_json)=0"
                  % (DB, TABLE)))
    print("  name 带首尾空格的行 %s（必须是 0）"
          % query("SELECT COUNT(*) FROM %s.%s WHERE name<>TRIM(name)" % (DB, TABLE)))
    print("  steps_json='[]' 的行 %s（预期 5）"
          % query("SELECT COUNT(*) FROM %s.%s WHERE steps_json='[]'" % (DB, TABLE)))


def main():
    ap = argparse.ArgumentParser(description="导入 free-exercise-db-zh 到 fm_plan.exercise（默认 dry-run）")
    ap.add_argument("--write", action="store_true", help="真的写库（不加就是 dry-run）")
    ap.add_argument("--dry-run", action="store_true", help="显式声明 dry-run（默认行为）")
    ap.add_argument("--limit", type=int, default=0, help="只处理前 N 条（自测用）")
    ap.add_argument("--refresh", action="store_true", help="忽略本地缓存，重新下载")
    args = ap.parse_args()

    will_write = args.write and not args.dry_run

    data, source = download(refresh=args.refresh)
    print("数据集 %d 条（来源：%s）" % (len(data), source))

    rows, unknown, skipped = build_rows(data)
    if args.limit and args.limit > 0:
        rows = rows[:args.limit]

    ok_unknown, ok_dist = report(rows, unknown, skipped, args.limit or 20, will_write)

    if not ok_unknown:
        print("\n❌ 有未覆盖的英文值，已中止（不写库）。")
        return 2

    if not will_write:
        return 0

    before = query("SELECT COUNT(*) FROM %s.%s" % (DB, TABLE))
    run_mysql_file(to_sql(rows))
    after = query("SELECT COUNT(*) FROM %s.%s" % (DB, TABLE))
    print("\n库内 %s 行数：%s → %s（本次提交 %d 条，其余是本来就在的）"
          % (TABLE, before, after, len(rows)))
    reread()
    return 0 if ok_dist else 0


if __name__ == "__main__":
    sys.exit(main())
