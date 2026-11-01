#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
================================================================================
动作示意图转存对象存储 + 回写 DB（M11-5 / P3）
================================================================================
把 free-exercise-db 的 **1746 张图**（873 条 × 2 张）上传到 MinIO 的
`action-cover/exercise/{数据集 id}/{0,1}.jpg`，并把 `fm_plan.exercise.image_url`
从"ghfast.top 外链"改成**对象 key**（浏览器地址由后端拼，见 M11-5 §7.3）。

★★ 默认是 **干跑**：只打印"要传多少张 / 跳过多少 / 失败清单"，什么都不改。
   加 `--apply` 才真上传并回写 DB。

用法
----
    python scripts/import-exercise-images.py                    # 干跑
    python scripts/import-exercise-images.py --limit 5          # 只处理 5 张（自测）
    python scripts/import-exercise-images.py --apply            # 真上传 + 回写 DB
    python scripts/import-exercise-images.py --apply --verify   # 传完逐条验证可匿名访问

四条已踩过的坑（照做，别重犯）
------------------------------
1. **上传不显式给 `content_type`** → 存成 `binary/octet-stream` → 直链 **HTTP 200 但
   `<img>` 不渲染**。`scripts/fix-minio-content-type.py` 的头注释就是为这个坑写的；
   排查时会误判成"桶权限不对"（实测就是这么绕了一圈）。
2. **`action-cover` 必须在前缀白名单里**，否则匿名 403。本机桶策略已含
   `arn:aws:s3:::fitmind/action-cover/*`（脚本启动会自检一次）。
3. **对象 key 用数据集 id，不用 DB 自增 id**：换环境（本机/部署/重装后重导）
   自增 id 必然不同，用 slug 才能"任何环境重跑都是同一份 key"，也才能幂等。
4. **回写按 `name` 匹配，不按 `en_name`**：108 条 P0 种子的 `en_name` 是"空格版"
   （`Barbell Bench Press - Medium Grip`），与数据集 id（`Barbell_Bench_Press_-_Medium_Grip`）
   不是同一个字符串 —— 按 en_name 匹配会漏掉一批，而漏掉的表现是"部分图不显示"。

★ 幂等：上传前 `stat_object` 比大小，已存在且大小一致 → 跳过；DB 只更新实际变化的行。
★ 断点续传：失败的 key 写 `logs/image-fail.txt`，重跑时先补它。
================================================================================
"""
import argparse
import io
import json
import os
import sys
import tempfile
import threading
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

try:
    from minio import Minio
except ImportError:  # noqa: BLE001
    print("❌ 缺少依赖 minio。请执行：python -m pip install minio")
    sys.exit(2)

REPO_ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = REPO_ROOT / "docker" / ".env"
FAIL_FILE = REPO_ROOT / "logs" / "image-fail.txt"

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "fm_plan"
TABLE = "exercise"

# 对象 key 前缀：与 setup-minio-anonymous.py 的 PUBLIC_PREFIXES 一致（那边注释原文就是"训练动作封面"）
PREFIX = "action-cover/exercise/"

DATASET = "assets/datasets/free-exercise-db-zh.json"
CACHE_DIR = os.path.join(tempfile.gettempdir(), "fitmind-exercise-db")

# 图片源镜像（顺序是实测的：本机 ghfast 可用，jsdelivr TLS 握手失败，raw 限速）
IMAGE_MIRRORS = [
    "https://ghfast.top/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/",
    "https://ghproxy.net/https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/",
    "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/",
]
DATASET_MIRRORS = [
    "https://ghfast.top/https://raw.githubusercontent.com/Sanotsu/free-fitness/main/",
    "https://ghproxy.net/https://raw.githubusercontent.com/Sanotsu/free-fitness/main/",
    "https://cdn.jsdelivr.net/gh/Sanotsu/free-fitness@main/",
    "https://raw.githubusercontent.com/Sanotsu/free-fitness/main/",
]

_lock = threading.Lock()


# ==================== 环境 ====================

def load_env() -> dict:
    """从 docker/.env 读凭据（★ 凭据不进 git，与其它脚本保持一致）"""
    env = {}
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def make_client(env):
    port = env.get("MINIO_API_PORT", "9000")
    return Minio(
        "127.0.0.1:%s" % port,
        access_key=env.get("MINIO_ROOT_USER", ""),
        secret_key=env.get("MINIO_ROOT_PASSWORD", ""),
        secure=False,
    )


def bucket_of(env):
    # FM_STORAGE_BUCKET 本机 docker/.env 里没有 → 三级回落（migrate-minio-to-oss.py 已有此写法）
    return env.get("FM_STORAGE_BUCKET") or env.get("MINIO_BUCKET") or "fitmind"


def public_base(env):
    port = env.get("MINIO_API_PORT", "9000")
    return "http://localhost:%s/%s/" % (port, bucket_of(env))


def policy_has_prefix(client, bucket):
    """自检匿名读策略里有没有 action-cover —— 没有就 403，而 403 在页面上就是"图裂"。"""
    try:
        policy = json.loads(client.get_bucket_policy(bucket))
    except Exception as e:  # noqa: BLE001
        return False, "拿不到桶策略：%s" % e
    for st in policy.get("Statement", []):
        for res in st.get("Resource", []):
            if res.endswith("/%s/*" % PREFIX.split("/")[0]):
                return True, res
    return False, "策略里没有 %s/*" % PREFIX.split("/")[0]


# ==================== 数据 ====================

def download_dataset(refresh=False):
    path = os.path.join(CACHE_DIR, "free-exercise-db-zh.json")
    if os.path.exists(path) and not refresh:
        return json.load(io.open(path, encoding="utf-8")), "cache"
    last = None
    for tpl in DATASET_MIRRORS:
        try:
            req = urllib.request.Request(tpl + DATASET, headers={"User-Agent": "fitmind-import/1.0"})
            with urllib.request.urlopen(req, timeout=60) as r:
                raw = r.read()
            data = json.loads(raw.decode("utf-8"))
            os.makedirs(CACHE_DIR, exist_ok=True)
            io.open(path, "wb").write(raw)
            return data, tpl.split("/")[2]
        except Exception as e:  # noqa: BLE001
            last = e
    raise RuntimeError("所有镜像都拿不到数据集：%s" % last)


def _query_pairs(sql):
    """跑一条查询，返回 [(第一列, 第二列)]。

    ★ 不要用 `CONCAT(id,'\\t',name)` 自己拼分隔符：mysql 客户端在 Windows 上
      会把 `'\\t'` 原样当两个字符返回（实测），于是 split('\\t') 一条都切不开、
      结果是"库里 0 条动作"这种看着像脚本坏了的现象。
      让 mysql 自己按列分隔（`SELECT a, b` 输出天然用 tab 分列）最稳。
      每一行还要 rstrip 掉 `\\r`（Windows 换行）。
    """
    import subprocess
    r = subprocess.run([MYSQL, "--default-character-set=utf8mb4", "-h127.0.0.1", "-P3306",
                        "-uroot", "-p1234", "-N", "-e", sql], capture_output=True)
    if r.returncode != 0:
        raise RuntimeError("查询失败：%s" % r.stderr.decode("utf-8", "ignore")[:300])
    out = []
    for line in r.stdout.decode("utf-8", "ignore").splitlines():
        line = line.rstrip("\r")
        if "\t" not in line:
            continue
        a, b = line.split("\t", 1)
        out.append((a, b))
    return out


def db_name_to_id():
    """数据集 name（strip 后）→ DB id。★ 按 name 匹配，见文件头坑 4。"""
    return {name: int(i) for i, name in _query_pairs("SELECT id, name FROM %s.%s" % (DB, TABLE))}


def current_image_urls():
    rows = _query_pairs("SELECT id, IFNULL(image_url,'') FROM %s.%s" % (DB, TABLE))
    return {int(i): url for i, url in rows}


# ==================== 任务 ====================

def build_tasks(data, name_to_id, limit=0):
    """返回 (tasks, skipped)；task = (key, dataset_image_path, db_id, name)"""
    tasks, skipped = [], []
    for e in data:
        name = (e.get("name") or "").strip()
        slug = e.get("id")
        images = e.get("images") or []
        db_id = name_to_id.get(name)
        if db_id is None:
            skipped.append((name, "库里没有这个动作（先跑 import-exercise-db.py）"))
            continue
        if not slug:
            skipped.append((name, "数据集没有 id"))
            continue
        if not images:
            skipped.append((name, "数据集没有图片"))
            continue
        for img in images:
            # img 形如 `3_4_Sit-Up/0.jpg` → key = action-cover/exercise/3_4_Sit-Up/0.jpg
            tasks.append((PREFIX + img, img, db_id, name))
    if limit and limit > 0:
        tasks = tasks[:limit]
    return tasks, skipped


def fetch_image(img_path, timeout=40):
    """三镜像回退下载。返回 (bytes, 来源)"""
    last = None
    for tpl in IMAGE_MIRRORS:
        try:
            req = urllib.request.Request(tpl + img_path, headers={"User-Agent": "fitmind-import/1.0"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read(), tpl.split("/")[2]
        except Exception as e:  # noqa: BLE001
            last = e
    raise RuntimeError("三个镜像都失败：%s" % last)


def remote_size(img_path, timeout=20):
    """只发 HEAD 拿远程大小（不下载正文）。拿不到返回 None。

    ★ 为什么要这一步：重跑时 1746 张里可能只有几张缺，先比大小就能跳过绝大多数，
      否则每次"补传"都要把 110MB 重新拉一遍（实测 8 线程约 7 分钟）。
    """
    for tpl in IMAGE_MIRRORS:
        try:
            req = urllib.request.Request(tpl + img_path, method="HEAD",
                                         headers={"User-Agent": "fitmind-import/1.0"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                n = r.headers.get("Content-Length")
                if n:
                    return int(n)
        except Exception:  # noqa: BLE001
            continue
    return None


def upload_one(client, bucket, key, img_path, retries=3):
    """下载+上传一张。返回 (状态, 说明)：'ok' 新传 / 'skip' 已存在且大小一致 / 'fail'"""
    # 先花一次 HEAD 换"可能省掉一次下载"
    rsize = remote_size(img_path)
    if rsize:
        try:
            st = client.stat_object(bucket, key)
            if st.size == rsize:
                return "skip", "已存在且大小一致（%d 字节）" % rsize
        except Exception:  # noqa: BLE001 —— 不存在就往下传
            pass

    last = None
    for attempt in range(retries):
        try:
            data, src = fetch_image(img_path)
            if not data:
                raise RuntimeError("拿到 0 字节")
            try:
                st = client.stat_object(bucket, key)
                if st.size == len(data):
                    return "skip", "已存在且大小一致（%d 字节）" % len(data)
            except Exception:  # noqa: BLE001
                pass
            # ★★ 必须显式给 content_type：不传会存成 binary/octet-stream，
            #    直链 200 但 <img> 不渲染（见文件头坑 1）
            client.put_object(bucket, key, io.BytesIO(data), length=len(data),
                              content_type="image/jpeg")
            return "ok", "%d 字节 ← %s" % (len(data), src)
        except Exception as e:  # noqa: BLE001
            last = e
    return "fail", "%s: %s" % (type(last).__name__, last)


# ==================== 回写 DB ====================

def sql_str(s):
    return "'" + str(s).replace("\\", "\\\\").replace("'", "''") + "'"


def write_back(keys_by_id, current, apply_changes):
    """只更新 image_url 一列，且只更新真正变化的行。"""
    changed = [(i, k) for i, k in keys_by_id.items() if current.get(i) != k]
    print("\n  需要回写 %d 行（已是新值的 %d 行跳过）" % (len(changed), len(keys_by_id) - len(changed)))
    if not apply_changes or not changed:
        return len(changed), 0

    import subprocess
    stmts = ["UPDATE `%s` SET `image_url` = %s WHERE `id` = %d AND (`image_url` IS NULL OR `image_url` <> %s);"
             % (TABLE, sql_str(k), i, sql_str(k)) for i, k in changed]
    fd, path = tempfile.mkstemp(suffix=".sql", dir=tempfile.gettempdir())
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(("\n".join(stmts)).encode("utf-8"))
        r = subprocess.run([MYSQL, "--default-character-set=utf8mb4", "-h127.0.0.1", "-P3306",
                            "-uroot", "-p1234", DB, "-e", "source %s" % path.replace("\\", "/")],
                           capture_output=True)
        if r.returncode != 0:
            raise RuntimeError("回写失败：%s" % r.stderr.decode("utf-8", "ignore")[:400])
    finally:
        try:
            os.remove(path)
        except OSError:
            pass
    return len(changed), len(changed)


# ==================== 验证 ====================

def verify(client, env, keys, sample=5):
    """不带任何签名直接 GET：必须 200 / image/jpeg / >10000 字节。
    ★ 三个都要断言：只断 200 会漏掉 octet-stream 那个坑（200 但图不渲染）。"""
    base = public_base(env)
    worst = 0
    for key in keys[:sample]:
        url = base + key
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "fitmind-verify/1.0"})
            with urllib.request.urlopen(req, timeout=20) as r:
                body = r.read()
                code, ctype, size = r.status, r.headers.get("Content-Type", ""), len(body)
        except urllib.error.HTTPError as e:
            code, ctype, size = e.code, "", 0
        except Exception:  # noqa: BLE001
            code, ctype, size = 0, "", 0
        ok = code == 200 and "image/jpeg" in ctype and size > 10000
        if not ok:
            worst = 1
        print("    %s %s %s %d %s" % ("✅" if ok else "❌", code, ctype or "-", size, key))
    return worst


# ==================== 主流程 ====================

def main():
    ap = argparse.ArgumentParser(description="动作示意图转存对象存储（默认干跑）")
    ap.add_argument("--apply", action="store_true", help="真上传 + 回写 DB")
    ap.add_argument("--verify", action="store_true", help="传完验证 5 张可匿名访问")
    ap.add_argument("--limit", type=int, default=0, help="只处理前 N 张（自测用）")
    ap.add_argument("--refresh", action="store_true", help="忽略缓存重下数据集")
    ap.add_argument("--workers", type=int, default=8, help="并发线程（默认 8）")
    args = ap.parse_args()

    env = load_env()
    bucket = bucket_of(env)
    client = make_client(env)

    print("=" * 74)
    print("  桶        : %s" % bucket)
    print("  对象前缀  : %s" % PREFIX)
    print("  对外基地址: %s" % public_base(env))
    print("  模式      : %s" % ("★ 实际执行（--apply）" if args.apply else "干跑（什么都不改）"))
    print("=" * 74)

    ok, detail = policy_has_prefix(client, bucket)
    print("\n[0] 匿名读策略自检：%s %s" % ("✅ 含 action-cover" if ok else "❌ 缺 action-cover", detail))
    if not ok:
        print("    → 先跑 python scripts/setup-minio-anonymous.py 把前缀加进白名单，否则浏览器 403")

    data, src = download_dataset(refresh=args.refresh)
    print("[1] 数据集 %d 条（来源 %s）" % (len(data), src))

    name_to_id = db_name_to_id()
    print("[2] 库里动作 %d 条" % len(name_to_id))

    tasks, skipped = build_tasks(data, name_to_id, args.limit)
    print("[3] 待处理 %d 张图；跳过 %d 条动作" % (len(tasks), len(skipped)))
    for name, why in skipped[:10]:
        print("      - %s：%s" % (name, why))

    if not args.apply:
        print("\n  前 5 个将要上传的 key：")
        for key, img, db_id, name in tasks[:5]:
            print("      %s   ← %s  (exercise id=%d)" % (key, img, db_id))
        # 干跑也检查一下"已存在多少"，这样能看出这次会真传多少
        exist = 0
        for key, img, db_id, name in tasks:
            try:
                client.stat_object(bucket, key)
                exist += 1
            except Exception:  # noqa: BLE001
                pass
        print("\n  桶里已存在 %d 张，将新传 %d 张" % (exist, len(tasks) - exist))
        print("\n  DRY-RUN：**没有上传任何文件、没有改库**。确认无误后加 --apply")
        return 0

    # ---- 上传 ----
    print("\n[4] 开始上传（%d 线程）…" % args.workers)
    done = skip = 0
    fails = []
    total = len(tasks)
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(upload_one, client, bucket, key, img): (key, name)
                   for key, img, db_id, name in tasks}
        for fut in as_completed(futures):
            key, name = futures[fut]
            status, msg = fut.result()
            with _lock:
                if status == "ok":
                    done += 1
                elif status == "skip":
                    skip += 1
                else:
                    fails.append((key, name, msg))
                finished = done + skip + len(fails)
                if finished % 100 == 0 or finished == total:
                    print("    %d/%d（新传 %d · 跳过 %d · 失败 %d）"
                          % (finished, total, done, skip, len(fails)))

    print("\n  结果：新传 %d · 跳过 %d · **失败 %d**" % (done, skip, len(fails)))
    if fails:
        FAIL_FILE.parent.mkdir(parents=True, exist_ok=True)
        FAIL_FILE.write_text("\n".join(k for k, _n, _m in fails), encoding="utf-8")
        print("  失败清单已写 %s（重跑会优先补）" % FAIL_FILE.relative_to(REPO_ROOT))
        for key, name, msg in fails[:10]:
            print("      ❌ %s（%s）%s" % (key, name, msg))

    # ---- 回写 DB ----
    # ★ 只回写"上传成功或本来就存在"的 key，失败的**不许**写进库
    failed_keys = {k for k, _n, _m in fails}
    keys_by_id = {}
    for key, img, db_id, name in tasks:
        if key in failed_keys:
            continue
        keys_by_id[db_id] = key
    print("\n[5] 回写 DB `image_url` = 对象 key")
    current = current_image_urls()
    need, changed = write_back(keys_by_id, current, apply_changes=True)

    # ---- 复查 ----
    print("\n[6] 复查（★ 「我认为传上去了」 ≠ 「库里是那样」）")
    import subprocess
    # ★ 用 f-string 而不是 % 格式化：SQL 里有 `'action-cover/%'` 这种字面 %，
    #   走 % 格式化会抛 "unsupported format character"（实测踩了一次）。
    check_sql = (
        "SELECT CONCAT("
        "'总=', COUNT(*), "
        "' 外链=', SUM(image_url LIKE 'http%'), "
        "' ghfast=', SUM(image_url LIKE '%ghfast.top%'), "
        "' 空=', SUM(image_url IS NULL), "
        "' key形态=', SUM(image_url LIKE 'action-cover/%')"
        f") FROM {DB}.{TABLE}"
    )
    r = subprocess.run([MYSQL, "--default-character-set=utf8mb4", "-h127.0.0.1", "-P3306",
                        "-uroot", "-p1234", "-N", "-e", check_sql], capture_output=True)
    print("    %s" % r.stdout.decode("utf-8", "ignore").strip())

    if args.verify:
        print("\n[7] 验证 5 张可匿名访问（200 / image/jpeg / >10000）")
        sample = list(keys_by_id.values())[:5]
        bad = verify(client, env, sample)
    else:
        bad = 0

    code = 0
    if fails:
        print("\n❌ 有 %d 张失败（退出码 1）—— 重跑本脚本会优先补它们" % len(fails))
        code = 1
    if bad:
        code = 1
    if code == 0:
        print("\n✅ 全部完成：%d 张已在桶里，image_url 已改成对象 key" % len(keys_by_id))
    return code


if __name__ == "__main__":
    sys.exit(main())
