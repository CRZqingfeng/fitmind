"""
给 MinIO 的 fitmind 桶设置「匿名只读」策略（可逆）

## 为什么需要

课程封面/动作视频存的是 MinIO 直链（`http://localhost:9000/fitmind/<fileId>`），
而浏览器 `<img src="...">` **不能携带 Authorization 头**。
桶默认私有时，直链会返回 403 —— 前端只能看到降级后的渐变色块。

## 这个脚本做了什么

只加一条 `s3:GetObject` 的允许规则（**不给 ListBucket**）：
匿名可以按完整 key 取到对象，但**不能枚举桶里有哪些文件**。
这比"把桶设成公开可列目录"安全得多。

## 回滚

    python scripts/setup-minio-anonymous.py --revert

`--revert` 会删除桶策略，桶立刻回到私有状态（默认拒绝匿名访问）。
本脚本**只改桶策略，不动任何对象数据**。

## 用法

    python scripts/setup-minio-anonymous.py            # 设置（先打印原策略）
    python scripts/setup-minio-anonymous.py --show     # 只查看当前策略
    python scripts/setup-minio-anonymous.py --revert   # 回滚为私有
"""

import datetime
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

from minio import Minio
from minio.error import S3Error

REPO_ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = REPO_ROOT / "docker" / ".env"
BUCKET = "fitmind"

# 允许匿名读取的 key 前缀（与 `fm-media` 的 `BizType` 枚举一一对应）。
# ★ 视频在 `media/` 前缀下，**刻意不在此列** —— 它必须走预签名地址访问。
PUBLIC_PREFIXES = [
    "avatar",  # AVATAR       头像
    "course-cover",  # COURSE_COVER 课程封面
    "action-cover",  # ACTION_COVER 动作封面
    "media-cover",  # MEDIA_COVER  媒资封面
    "common",  # COMMON       通用
]

# ★★ `media/` 下的**图片**例外 —— 这是实测纠正后的必要折中。
#
#   实测发现 `media/` 前缀里**混着封面图**：
#       media/*.mp4  617 个（课程视频 / 学员动作视频）
#       media/*.svg   43 个（课程封面，其中 11 个被 course.cover_url 引用）
#       media/*.png   10 个（封面）
#   根因：seed 的 `uploadAsset()` 对"视频"和"封面"用的是**同一条链路**
#   （都走 `/ms/medias`），所以 key 前缀都是 `media/` —— **前缀天然区分不了**。
#
#   ⚠️ 为什么可以接受这个折中：它的**失败模式是安全的** ——
#     将来新增图片格式（如 .avif）最多让**封面破图**（前端本来就有渐变兜底），
#     而视频扩展名（mp4 / webm / mov / avi）**永远不在这个列表里**，放不出去。
#
#   ★ 理想形态是"封面根本不落在 media/"（改 seed 用 `course-cover` bizType + 迁存量），
#     已登记为后续项；等存量迁完，这个扩展名例外就可以删掉。
PUBLIC_MEDIA_IMAGE_EXTS = [".svg", ".png", ".jpg", ".jpeg", ".webp", ".gif"]

# 只读策略：允许匿名 GetObject，不给 ListBucket（不能枚举目录）
READ_ONLY_POLICY = {
    "Version": "2012-10-17",
    "Statement": [
        {
            "Sid": "PublicReadOnlyForAssets",
            "Effect": "Allow",
            "Principal": {"AWS": ["*"]},
            "Action": ["s3:GetObject"],
            # ★★ 收窄到「资源前缀白名单」，而不是整个桶：
            #   原来写 `{BUCKET}/*` —— 整个桶匿名可读，于是**付费课程视频与
            #   学员的动作考核视频也能被匿名下载**：
            #     · `play-url` 返回的预签名地址里**带着对象 key**，前端拿得到；
            #     · 桶匿名可读时，把签名参数去掉直接 GET 就能下走整个视频。
            #   而白名单里这 5 个前缀都是"本来就要展示给人看"的资源（头像 / 各类封面）。
            #   —— 为什么用白名单而不是 NotResource 排除 `media/*`：
            #      白名单是**默认拒绝**，以后新增业务前缀不会被意外公开。
            "Resource": [f"arn:aws:s3:::{BUCKET}/{p}/*" for p in PUBLIC_PREFIXES]
            # ★ media/ 下的图片（封面）—— 见 PUBLIC_MEDIA_IMAGE_EXTS 的说明
            + [f"arn:aws:s3:::{BUCKET}/media/*{e}" for e in PUBLIC_MEDIA_IMAGE_EXTS],
        }
    ],
}


def load_env() -> dict:
    """从 docker/.env 读凭据（与 scripts/probe-minio.py 保持一致，凭据不进 git）"""
    if not ENV_FILE.exists():
        sys.exit(f"❌ 找不到 {ENV_FILE}")
    env = {}
    for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def make_client(env: dict) -> Minio:
    port = env.get("MINIO_API_PORT", "9000")
    return Minio(
        f"localhost:{port}",
        access_key=env["MINIO_ROOT_USER"],
        secret_key=env["MINIO_ROOT_PASSWORD"],
        secure=False,
    )


def show_policy(client: Minio) -> str | None:
    try:
        policy = client.get_bucket_policy(BUCKET)
        print("  当前桶策略：")
        print("  " + json.dumps(json.loads(policy), ensure_ascii=False, indent=2).replace("\n", "\n  "))
        return policy
    except S3Error as e:
        if e.code in ("NoSuchBucketPolicy", "NoSuchBucket"):
            print("  当前桶策略：**无**（桶为私有 —— 匿名访问会被拒绝）")
            return None
        raise


def is_public_key(key: str) -> bool:
    """按当前策略判断某个 key **应该**能匿名读到。

    ★ 这份判断必须与 `READ_ONLY_POLICY` 保持一致 —— 它是**断言的口径来源**。
      两处不一致就会出现"策略是对的、脚本却报失败"，实测踩过一次。
    """
    top = key.split("/", 1)[0]
    if top in PUBLIC_PREFIXES:
        return True
    # media/ 下只有图片是公开的（封面与视频同前缀，只能靠扩展名区分）
    return top == "media" and key.lower().endswith(tuple(PUBLIC_MEDIA_IMAGE_EXTS))


def pick_probe_objects(client: Minio) -> tuple[str | None, str | None]:
    """挑一对探测对象：(**应该公开**的, **应该私有**的)，用于双向验证。

    为什么要"一对"而不是"随便挑一个"：这个脚本原先只挑一个对象、且假设
    **所有对象都能匿名读**。策略收窄后该假设不再成立 —— 它挑中的往往是视频，
    于是一边报"匿名读未生效"，一边其实**正是想要的结果**（视频被拦住了）。
    """
    public = private = None
    for obj in client.list_objects(BUCKET, recursive=True):
        if is_public_key(obj.object_name):
            public = public or obj.object_name
        else:
            private = private or obj.object_name
        if public and private:
            break
    return public, private


def probe_anonymous(env: dict, key: str) -> int:
    """不带任何签名直接 GET，返回 HTTP 状态码（0 = 连不上）。"""
    url = f"http://localhost:{env.get('MINIO_API_PORT', '9000')}/{BUCKET}/{key}"
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return 0


def main() -> int:
    args = sys.argv[1:]
    mode = "set"
    if "--show" in args:
        mode = "show"
    elif "--revert" in args:
        mode = "revert"

    env = load_env()
    client = make_client(env)

    if not client.bucket_exists(BUCKET):
        sys.exit(f"❌ 桶 {BUCKET} 不存在")

    print(f"MinIO 桶：{BUCKET}\n")

    if mode == "show":
        show_policy(client)
        return 0

    if mode == "revert":
        print("【回滚】删除桶策略，恢复为私有")
        before = show_policy(client)
        if before is None:
            print("\n  桶本来就是私有的，无需回滚。")
            return 0
        client.delete_bucket_policy(BUCKET)
        print("\n  ✅ 桶策略已删除 —— 桶已恢复私有")
        pub, _ = pick_probe_objects(client)
        key = pub or next((o.object_name for o in client.list_objects(BUCKET, recursive=True)), None)
        if key:
            print(f"\n【验证】用对象 {key} 试探匿名访问（回滚后**期望被拒绝**）")
            code = probe_anonymous(env, key)
            if code == 200:
                print("  ⚠️ 仍然能匿名读到 —— 可能还有别的策略来源，请人工确认")
                return 1
            print(f"  ✅ 匿名访问被拒绝（HTTP {code}），回滚成功")
        return 0

    # ---- 设置 ----
    print("【1/3】记录原策略（便于回滚）")
    original = show_policy(client)
    if original is not None:
        backup = REPO_ROOT / "docker" / "minio-bucket-policy.backup.json"
        backup.write_text(original, encoding="utf-8")
        print(f"\n  原策略已备份到：{backup.relative_to(REPO_ROOT)}")

    print("\n【2/3】设置匿名只读策略（仅 s3:GetObject，不给 ListBucket）")
    client.set_bucket_policy(BUCKET, json.dumps(READ_ONLY_POLICY))
    print("  ✅ 已设置")

    print("\n【3/3】验证（★ 双向：公开的能读、视频读不到）")
    pub, priv = pick_probe_objects(client)
    if not pub and not priv:
        print("  ⚠️ 桶里没有对象，无法验证（上传后再跑 --show 与直链检查）")
        return 0
    ok = True
    if pub:
        code = probe_anonymous(env, pub)
        hit = code == 200
        ok = ok and hit
        mark = "✅ 可读（封面正常）" if hit else "❌ 期望 200 —— 封面会破图！"
        print(f"  [公开] {pub}")
        print(f"         匿名 GET → HTTP {code} {mark}")
    else:
        print("  [公开] 桶里没有公开前缀的对象，跳过")
    if priv:
        code = probe_anonymous(env, priv)
        hit = code in (401, 403)
        ok = ok and hit
        mark = "✅ 已拦下（视频受保护）" if hit else "❌ 期望 403 —— 视频可能仍可被匿名下载！"
        print(f"  [私有] {priv}")
        print(f"         匿名 GET → HTTP {code} {mark}")
    else:
        print("  [私有] 桶里没有需保护的对象，跳过")
    if ok:
        print("\n✅ 完成：封面可匿名读、视频已受保护")
        return 0
    print("\n❌ 验证未通过，请按上面的 [公开] / [私有] 分别检查")
    return 1
    return 1


if __name__ == "__main__":
    sys.exit(main())
