#!/usr/bin/env python
"""
把 MinIO 里的对象迁到阿里云 OSS，并把数据库里存的 URL 改成 OSS 形态（M9-1 第 2 期 B）

## 为什么需要

`fm.storage.type` 从 minio 切到 oss 之后，**对象本身不会自己过去** —— OSS 是全新的桶。
库里那些 `cover_url` / `media_url` 仍指向 MinIO，于是"切了云 = 封面和视频全没了"。

这个脚本把两件事一起做掉：

  **① 对象搬运**：MinIO bucket → OSS bucket
  **② 封面换前缀**：`media/*.svg|png|jpg…`（封面图）→ `media-cover/…`
      —— OSS 的匿名读白名单**刻意排除了 `media/` 整个前缀**（那里是视频，必须走预签名）。
         顺带把 #C31「封面与视频共用 media/ 前缀」这条债还掉。

★ **为什么是 `media-cover/` 而不是方案里写的 `course-cover/`**（实测后改的，不是笔误）：
  真正被引用的图片 key 一共 **53 个**，其中
    · **53 个**被 `fm_media.media.media_url` 引用（`cover-1.png` 之类，是**媒资封面**被存成了 media 记录）
    · 只有 **11 个**被 `fm_course.course.cover_url` 引用（且这 11 个**是那 53 个的子集**）
  也就是说这批数据的**主体是媒资封面**，`media-cover/` 才是与 `BizType` 语义对得上的前缀
  （另一个候选 `course-cover/` 也够用，但会把 43 个媒资封面叫成课程封面）。
  两者都在匿名读白名单里，功能等价 —— **选语义正确的那个**。

## ★ 会被改写的字段（实测出来的全集，不是猜的）

| 表.字段 | 实测行数 | 处理 |
|---|---|---|
| `fm_course.course.cover_url` | 11 | ★ **换前缀** + 换 host |
| `fm_trade.trade_order_detail.cover_url` | 12 | ⚠️ 是**下单时的快照**，也要换前缀（否则订单历史里封面裂） |
| `fm_media.media.media_url` | 768 | 仅换 host（视频留在 `media/`） |
| `fm_media.file.url` | 14 | 仅换 host |
| `fm_user.user_detail.icon` | 1 | 仅换 host（★ 头像在 `user_detail`，**不在** `user`） |

★ 快照字段这条是**实测才发现的**：只改 `course.cover_url` 会让订单详情页的封面变成 403
（因为快照里存的是旧 URL）。所以"换前缀"必须是**按旧 URL 全库替换**，不能只盯着主表。

## 幂等性

- 对象：先 `head_object` 比大小，**已存在且大小一致就跳过** → 可重复执行
- 数据库：按**精确的旧 URL** 替换，替换过的不会再匹配 → 可重复执行

## 用法

    python scripts/migrate-minio-to-oss.py           # 干跑（默认，什么都不改）
    python scripts/migrate-minio-to-oss.py --apply   # 执行

## 依赖

    pip install minio oss2 pymysql
"""

import argparse
import io
import sys
from collections import Counter
from pathlib import Path
from urllib.parse import urlparse

try:
    import oss2
    import pymysql
    from minio import Minio
except ImportError as e:  # noqa: BLE001
    print(f"❌ 缺少依赖：{e}\n   请执行：pip install minio oss2 pymysql")
    sys.exit(2)

REPO_ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = REPO_ROOT / "docker" / ".env"

# 图片扩展名 —— 只有这些才允许从 media/ 挪到 course-cover/
IMAGE_EXTS = {".svg", ".png", ".jpg", ".jpeg", ".webp", ".gif"}
# 目标前缀（与 OSS 匿名读白名单、BizType.MEDIA_COVER 一一对应）
# ★ 见文件头"为什么是 media-cover/ 而不是 course-cover/"
TARGET_PREFIX = "media-cover/"

# ★ 需要做「换 host」的字段全集（实测得出，见文件头表格）
URL_COLUMNS = [
    ("fm_course", "course", "cover_url"),
    ("fm_course", "course_draft", "cover_url"),
    ("fm_exam", "action_assessment", "video_url"),
    ("fm_media", "media", "media_url"),
    ("fm_media", "media", "cover_url"),
    ("fm_media", "file", "url"),
    ("fm_trade", "trade_cart", "cover_url"),
    ("fm_trade", "trade_order_detail", "cover_url"),
    ("fm_pay", "pay_order", "qr_code_url"),
    ("fm_user", "user_detail", "icon"),
    ("fm_user", "user_detail", "photo"),
]


def env_get(key: str, default: str = "") -> str:
    if not ENV_FILE.exists():
        return default
    for line in ENV_FILE.read_text(encoding="utf-8", errors="ignore").splitlines():
        if line.startswith(f"{key}="):
            return line.split("=", 1)[1].strip() or default
    return default


class Cfg:
    def __init__(self) -> None:
        self.minio_endpoint = env_get("MINIO_ENDPOINT", "http://localhost:9000")
        p = urlparse(self.minio_endpoint)
        self.minio_host = f"{p.hostname}:{p.port or (443 if p.scheme == 'https' else 80)}"
        self.minio_secure = p.scheme == "https"
        self.minio_public = env_get("MINIO_PUBLIC_ENDPOINT", self.minio_endpoint).rstrip("/")
        self.minio_bucket = env_get("FM_STORAGE_BUCKET", env_get("MINIO_BUCKET", "fitmind"))
        self.minio_ak = env_get("MINIO_ROOT_USER")
        self.minio_sk = env_get("MINIO_ROOT_PASSWORD")

        self.oss_endpoint = env_get("OSS_ENDPOINT")
        self.oss_host = self.oss_endpoint.split("://", 1)[-1].rstrip("/")
        self.oss_bucket = env_get("OSS_BUCKET")
        self.oss_ak = env_get("OSS_ACCESS_KEY_ID")
        self.oss_sk = env_get("OSS_ACCESS_KEY_SECRET")

        self.db = dict(
            host="127.0.0.1",
            port=3306,
            user="root",
            password=env_get("MYSQL_PASSWORD", "1234"),
            charset="utf8mb4",
        )

    @property
    def minio_url_prefix(self) -> str:
        return f"{self.minio_public}/{self.minio_bucket}/"

    @property
    def oss_url_prefix(self) -> str:
        return f"https://{self.oss_bucket}.{self.oss_host}/"

    def minio_url(self, key: str) -> str:
        return self.minio_url_prefix + key

    def oss_url(self, key: str) -> str:
        return self.oss_url_prefix + key


def main() -> int:
    parser = argparse.ArgumentParser(description="MinIO → OSS 对象与 URL 迁移（M9-1）")
    parser.add_argument("--apply", action="store_true", help="真正执行（默认只干跑）")
    args = parser.parse_args()
    dry = not args.apply

    cfg = Cfg()
    print("=" * 74)
    print(f"源    MinIO : {cfg.minio_endpoint}  bucket={cfg.minio_bucket}")
    print(f"目标  OSS   : {cfg.oss_endpoint}  bucket={cfg.oss_bucket}")
    print(f"模式        : {'干跑（什么都不改）' if dry else '★ 实际执行'}")
    print("=" * 74)

    minio_client = Minio(
        cfg.minio_host, access_key=cfg.minio_ak, secret_key=cfg.minio_sk, secure=cfg.minio_secure
    )
    oss_bucket = oss2.Bucket(oss2.Auth(cfg.oss_ak, cfg.oss_sk), f"https://{cfg.oss_host}", cfg.oss_bucket)

    conn = pymysql.connect(**cfg.db, cursorclass=pymysql.cursors.DictCursor)

    # ------------------------------------------------------------------
    # 1. 找"需要换前缀"的 key：凡是被任何 URL 字段引用到的 media/*.图片
    # ------------------------------------------------------------------
    referenced_keys = set()
    with conn.cursor() as cur:
        for schema, table, col in URL_COLUMNS:
            cur.execute(
                f"SELECT DISTINCT `{col}` AS u FROM `{schema}`.`{table}` "
                f"WHERE `{col}` LIKE %s",
                (cfg.minio_url_prefix + "%",),
            )
            for row in cur.fetchall():
                k = (row["u"] or "").split("?")[0]
                if k.startswith(cfg.minio_url_prefix):
                    referenced_keys.add(k[len(cfg.minio_url_prefix):])

    key_moves = {}
    for key in sorted(referenced_keys):
        if key.startswith("media/") and Path(key).suffix.lower() in IMAGE_EXTS:
            key_moves[key] = TARGET_PREFIX + key[len("media/"):]

    print(f"\n库里被引用的 MinIO 对象 key 共 {len(referenced_keys)} 个；需换前缀 {len(key_moves)} 个")
    for k, v in list(key_moves.items())[:6]:
        print(f"    {k}  →  {v}")
    if len(key_moves) > 6:
        print(f"    …（共 {len(key_moves)} 条）")

    # ------------------------------------------------------------------
    # 2. 扫描 MinIO，规划搬运
    # ------------------------------------------------------------------
    print("\n正在扫描 MinIO 对象…")
    objects = [o for o in minio_client.list_objects(cfg.minio_bucket, recursive=True) if not o.is_dir]
    total_mb = sum(o.size for o in objects) / 1048576
    print(f"MinIO 对象总数 = {len(objects)}，合计 {total_mb:.2f} MB")

    stats = Counter()
    to_copy = []
    for o in objects:
        old_key = o.object_name
        new_key = key_moves.get(old_key, old_key)
        stats["total"] += 1
        try:
            head = oss_bucket.head_object(new_key)
            if head.content_length == o.size:
                stats["skip"] += 1
                continue
        except oss2.exceptions.NoSuchKey:
            pass
        except oss2.exceptions.OssError as e:  # noqa: BLE001
            if e.status != 404:
                raise
        to_copy.append((old_key, new_key, o.size))

    print(f"  需搬运 {len(to_copy)} 个；已存在且大小一致 → 跳过 {stats['skip']} 个")

    if dry:
        print("\n（干跑结束。确认无误后加 --apply 执行。）")
        conn.close()
        return 0

    # ------------------------------------------------------------------
    # 3. 搬运对象
    # ------------------------------------------------------------------
    print("\n>>> 搬运对象…")
    done, failed = 0, []
    for old_key, new_key, _size in to_copy:
        try:
            resp = minio_client.get_object(cfg.minio_bucket, old_key)
            try:
                data = resp.read()
                ctype = resp.headers.get("Content-Type") or "application/octet-stream"
            finally:
                resp.close()
                resp.release_conn()
            oss_bucket.put_object(new_key, io.BytesIO(data), headers={"Content-Type": ctype})
            done += 1
            if done % 200 == 0:
                print(f"    已传 {done}/{len(to_copy)}")
        except Exception as e:  # noqa: BLE001
            failed.append((old_key, f"{type(e).__name__}: {e}"))
    print(f"  ✅ 已搬运 {done} 个；失败 {len(failed)} 个")
    for k, msg in failed[:10]:
        print(f"     ❌ {k} → {msg}")

    # ------------------------------------------------------------------
    # 4. 改写数据库 URL
    #    ★ 两步：先按"精确旧 URL"做换前缀（覆盖快照字段），再全量换 host
    # ------------------------------------------------------------------
    print("\n>>> 改写数据库 URL…")
    affected = Counter()
    with conn.cursor() as cur:
        # ① 换前缀（精确匹配，跨所有 URL 字段 —— 快照字段才会被覆盖到）
        for old_key, new_key in key_moves.items():
            old_url, new_url = cfg.minio_url(old_key), cfg.oss_url(new_key)
            for schema, table, col in URL_COLUMNS:
                cur.execute(
                    f"UPDATE `{schema}`.`{table}` SET `{col}`=%s WHERE `{col}`=%s",
                    (new_url, old_url),
                )
                if cur.rowcount:
                    affected[f"{schema}.{table}.{col}(换前缀)"] += cur.rowcount

        # ② 其余：只换 host
        for schema, table, col in URL_COLUMNS:
            cur.execute(
                f"UPDATE `{schema}`.`{table}` SET `{col}`=REPLACE(`{col}`, %s, %s) "
                f"WHERE `{col}` LIKE %s",
                (cfg.minio_url_prefix, cfg.oss_url_prefix, cfg.minio_url_prefix + "%"),
            )
            if cur.rowcount:
                affected[f"{schema}.{table}.{col}"] += cur.rowcount
    conn.commit()

    for k, v in affected.items():
        print(f"    {k}：{v} 行")

    # ------------------------------------------------------------------
    # 5. 自检：库里不该再有指向 MinIO 的 URL
    # ------------------------------------------------------------------
    print("\n>>> 自检")
    leftover = Counter()
    with conn.cursor() as cur:
        for schema, table, col in URL_COLUMNS:
            cur.execute(
                f"SELECT COUNT(*) AS c FROM `{schema}`.`{table}` WHERE `{col}` LIKE %s",
                (cfg.minio_url_prefix + "%",),
            )
            c = cur.fetchone()["c"]
            if c:
                leftover[f"{schema}.{table}.{col}"] = c
    conn.close()

    if leftover:
        print("  ❌ 仍有指向 MinIO 的 URL：")
        for k, v in leftover.items():
            print(f"     {k}：{v} 行")
    else:
        print("  ✅ 库里已无指向 MinIO 的 URL")

    print("\n下一步：跑一次验收确认双向匿名读（图片 200 / 视频 403）")
    return 0 if not failed and not leftover else 1


if __name__ == "__main__":
    sys.exit(main())
