#!/usr/bin/env python
"""
给阿里云 OSS 的桶设置「按前缀的匿名只读」策略（M9-1，对标 setup-minio-anonymous.py）

## 为什么需要

课程封面 / 头像库里存的是 **rawUrl（不带签名）**，前端直接塞进 `<img src>`。
而 `<img>` **不能携带 Authorization 头** —— 桶完全私有时**全站封面 403**。

这在 MinIO 那边是 #B3 处理过的问题（`scripts/setup-minio-anonymous.py`），
换到 OSS 必须做**等价的一步**，否则"切了云，封面全没了"。

## 这个脚本做什么（三步）

  ① 把**桶 ACL 设成 private**（阿里云默认就是 private）
  ② 加一条 **Bucket Policy**：只允许匿名 `oss:GetObject`
     读取 `avatar/`、`course-cover/`、`action-cover/`、`media-cover/`、`common/` 这几个前缀
  ③ 打印结果供核对

★ **`media/` 前缀刻意不在白名单里** —— 那是**课程视频与学员动作考核视频**，
  必须走预签名地址访问（`/ms/medias/{id}/play-url`）。
  这与 MinIO 侧的策略**语义完全一致**，也是 #B3 的核心成果，切换存储不能把它弄丢。

## ★★ 为什么必须先改 ACL

实测（2026-10-01）：这个桶原本是 **`public-read`**（桶级 ACL）。
桶级 ACL 一旦是 public-read，**桶内所有对象都可匿名读**，且**无法按前缀排除** ——
也就是说**视频也能被匿名下载**（正是 #B3 修掉的那个漏洞）。
所以要先把 ACL 收回 private，再用 Policy 精确放行图片前缀。

⚠️ 改 ACL 是**桶级**操作，会影响桶内**所有**对象（包括别的用途的文件）。
   本脚本会在动手前把当前 ACL 与策略打印出来，请先看一眼再确认。

## 依赖

    pip install oss2        # 官方 Python SDK

## 用法

    python scripts/setup-oss-public-prefix.py --show      # 只看现状（默认动作，安全）
    python scripts/setup-oss-public-prefix.py --apply     # 收紧 ACL + 写前缀策略
    python scripts/setup-oss-public-prefix.py --revert    # 删掉策略（ACL 保持 private）

★ 与 MinIO 那份的区别：那份**默认就是 apply**；这里默认是 `--show`，
  因为本脚本会动**桶级 ACL**，爆炸半径比"加一条对象前缀策略"大，宁可多打一个参数。
"""

import argparse
import json
import sys
from pathlib import Path

try:
    import oss2
except ImportError:
    print("❌ 缺少依赖 oss2，请先执行：pip install oss2")
    sys.exit(2)

REPO_ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = REPO_ROOT / "docker" / ".env"

# 阿里云账号 UID（Bucket Policy 的 Resource ARN 里要写）。
# 取自用户提供值；换账号时改这里。
ACCOUNT_UID = "1318299528638066"

# ★ 允许匿名读取的 key 前缀 —— 与 fm-media 的 BizType 枚举**一一对应**。
#   改 BizType 必须同步改这里（反之亦然）。
PUBLIC_PREFIXES = [
    "avatar",        # AVATAR        用户头像
    "course-cover",  # COURSE_COVER  课程封面
    "action-cover",  # ACTION_COVER  训练动作封面
    "media-cover",   # MEDIA_COVER   媒资封面
    "common",        # COMMON        通用兜底
]
# ★ 刻意**不在**白名单里的前缀：
#   media/  —— 课程视频 + 学员动作考核视频（必须走预签名）


def env_get(key: str) -> str:
    """从 docker/.env 取一个值（与其它脚本同一套约定）"""
    if not ENV_FILE.exists():
        return ""
    for line in ENV_FILE.read_text(encoding="utf-8", errors="ignore").splitlines():
        if line.startswith(f"{key}="):
            return line.split("=", 1)[1].strip()
    return ""


def build_policy(bucket: str) -> str:
    resources = [
        f"acs:oss:*:{ACCOUNT_UID}:{bucket}/{p}/*" for p in PUBLIC_PREFIXES
    ]
    return json.dumps(
        {
            "Version": "1",
            "Statement": [
                {
                    "Effect": "Allow",
                    "Action": ["oss:GetObject"],
                    "Principal": ["*"],
                    "Resource": resources,
                }
            ],
        },
        indent=2,
        ensure_ascii=False,
    )


def show(bucket_obj, label: str) -> None:
    print(f"\n===== {label} =====")
    try:
        acl = bucket_obj.get_bucket_acl().acl
        print(f"桶 ACL            : {acl}")
    except Exception as e:  # noqa: BLE001
        print(f"桶 ACL 读取失败   : {e}")
    try:
        # ⚠️ get_bucket_policy() 返回的是结果对象，不是字符串 —— 直接打印会得到
        #    `<oss2.models.GetBucketPolicyResult object at 0x...>`，什么都看不到。
        result = bucket_obj.get_bucket_policy()
        policy = getattr(result, "policy", None) or str(result)
        print(f"Bucket Policy     :\n{policy.strip() or '(空 —— 没有桶策略)'}")
    except Exception as e:  # noqa: BLE001
        print(f"Bucket Policy     : 读取失败（{e}）")
    try:
        cors = bucket_obj.get_bucket_cors()
        rules = getattr(cors, "rules", None) or []
        if not rules:
            print("CORS              : (无规则 —— ★ 浏览器直传会失败)")
        else:
            for r in rules:
                print(f"CORS              : origins={r.allowed_origins} methods={r.allowed_methods} "
                      f"headers={r.allowed_headers} expose={r.expose_headers}")
    except Exception as e:  # noqa: BLE001
        print(f"CORS              : 读取失败（{e}）")


def apply_cors(bucket_obj) -> None:
    """
    浏览器**直传视频**必须靠 CORS（`POST /ms/medias/upload-signature` → 浏览器 PUT OSS）。

    ★ 图片不需要：图片走 `POST /ms/files` 是**服务端中转**，不跨域。
    ★ 为什么脚本要管这件事：不配 CORS 时，Node/curl 的 PUT 照样 200，
      只有**浏览器**会失败 —— 症状是"接口都对，页面上传就是不动"，
      属于最容易误判成前端 bug 的一类。
    """
    rule = oss2.models.CorsRule(
        # 演示期先放开；上线前收窄成实际域名（本地 5173/5174 + 线上域名）
        allowed_origins=["*"],
        allowed_methods=["PUT", "GET", "HEAD", "POST"],
        allowed_headers=["*"],
        # ETag 要暴露：前端/脚本有时要拿它做校验
        expose_headers=["ETag", "x-oss-request-id"],
        max_age_seconds=3600,
    )
    # ⚠️ 必须包成 `BucketCors([rule])` —— oss2 的 put_bucket_cors 签名是 (input)，
    #    但它内部走的是 `__convert_data(BucketCors, to_put_bucket_cors, input)`：
    #      · 传 `[rule]`（裸列表）→ requests 把它当表单数据 → TypeError: cannot unpack ...
    #      · 传 `rule`（裸对象）  → 报 "'body' must be a bytes-like object ... Instead was CorsRule"
    #    两种错法都**不会**在文档里写，而且一旦被 try/except 吞掉，
    #    表现就是"写了但没生效"（桶上仍是旧规则）—— 极难查。
    bucket_obj.put_bucket_cors(oss2.models.BucketCors([rule]))
    print("✅ CORS 规则已写入（origins=*  methods=PUT/GET/HEAD/POST  expose=ETag）")


def main() -> int:
    parser = argparse.ArgumentParser(description="OSS 匿名只读前缀白名单（M9-1）")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--show", action="store_true", help="只查看当前 ACL 与策略（默认）")
    group.add_argument("--apply", action="store_true", help="收紧 ACL 为 private 并写入前缀策略")
    group.add_argument("--revert", action="store_true", help="删除桶策略（ACL 保持 private）")
    args = parser.parse_args()

    endpoint = env_get("OSS_ENDPOINT")
    bucket = env_get("OSS_BUCKET")
    ak = env_get("OSS_ACCESS_KEY_ID")
    sk = env_get("OSS_ACCESS_KEY_SECRET")

    if not all([endpoint, bucket, ak, sk]):
        print("❌ 读不到 OSS 配置。请确认 docker/.env 里有：")
        print("   OSS_ENDPOINT / OSS_BUCKET / OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET")
        return 2

    # endpoint 可能是 https://xxx，oss2 只要 host 部分
    host = endpoint.split("://", 1)[-1].rstrip("/")
    auth = oss2.Auth(ak, sk)
    service = oss2.Service(auth, f"https://{host}")
    bucket_obj = oss2.Bucket(auth, f"https://{host}", bucket)

    print(f"bucket = {bucket}   endpoint = https://{host}")
    print(f"白名单前缀 = {PUBLIC_PREFIXES}")
    print(f"刻意排除   = ['media']（视频，必须走预签名）")

    show(bucket_obj, "变更前")

    if args.show or not (args.apply or args.revert):
        print("\n（这是只读查看。要动手请加 --apply；要撤销用 --revert）")
        return 0

    if args.revert:
        print("\n>>> 删除桶策略（ACL 保持 private，封面会变成 403，直到重新 --apply）")
        try:
            bucket_obj.delete_bucket_policy()
            print("✅ 桶策略已删除")
        except Exception as e:  # noqa: BLE001
            print(f"⚠️ 删除策略失败（可能本来就没有）：{e}")
        show(bucket_obj, "变更后")
        return 0

    # ---------- apply ----------
    print("\n>>> ① 设置桶 ACL = private")
    print("    ⚠️ 这是桶级操作：桶内**所有**对象都会失去匿名读，")
    print("       随后由第 ② 步的策略按前缀放行图片。视频将**永远**需要签名。")
    try:
        bucket_obj.put_bucket_acl(oss2.BUCKET_ACL_PRIVATE)
        print("✅ ACL 已设为 private")
    except Exception as e:  # noqa: BLE001
        print(f"❌ 设置 ACL 失败：{e}")
        return 1

    print("\n>>> ② 写入前缀白名单策略")
    policy = build_policy(bucket)
    print(policy)
    try:
        bucket_obj.put_bucket_policy(policy)
        print("✅ 策略已写入")
    except Exception as e:  # noqa: BLE001
        print(f"❌ 写入策略失败：{e}")
        return 1

    print("\n>>> ③ 写入 CORS 规则（浏览器直传视频要用）")
    try:
        apply_cors(bucket_obj)
    except Exception as e:  # noqa: BLE001
        print(f"⚠️ CORS 写入失败（不影响服务端中转，但浏览器直传会失败）：{e}")

    show(bucket_obj, "变更后")
    print(
        "\n下一步：跑一次媒资验收，确认**双向**结果 ——\n"
        "  · 图片（media-cover/…）：匿名 GET → 200\n"
        "  · 视频（media/…mp4）   ：匿名 GET → 403\n"
        "（`node scripts/accept-m2-1.mjs` 里有这两条断言）"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
