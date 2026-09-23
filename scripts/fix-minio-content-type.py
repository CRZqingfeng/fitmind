"""
修复 MinIO 里对象的 Content-Type（两阶段：默认只报告，--apply 才改）

## 为什么需要

上传行为（预签名 PUT）**不带 Content-Type** 时，MinIO 会把对象存成
`binary/octet-stream`。这有一个后果很多人不知道：

    <img src="http://…/cover.svg"> 在 Content-Type 不是 image/* 时
    **浏览器不会当图片渲染** —— 加载直接失败（触发 onerror），

于是前端只能显示降级后的渐变色块，而**对象本身其实读得到**（HTTP 200）。
排查时很容易误判成"桶权限不对"（实测就是这么绕了一圈：桶已开匿名只读、
直链 200，但封面依然不显示）。

## 这个脚本做什么

遍历桶里的对象，**按扩展名推断**正确的 Content-Type，就地改写对象的元数据
（`copy_object` + `metadata_directive=REPLACE`，只动 head 里的 Content-Type，
**不会重新上传、不会丢数据**）。

## 用法

    python scripts/fix-minio-content-type.py            # 阶段一：只报告，不写任何东西
    python scripts/fix-minio-content-type.py --apply     # 阶段二：执行修复

★ 两阶段是刻意的：`--apply` 前会先打印"将要改哪些对象、从什么改成什么"，
看清了再执行（本项目对"改"类操作的一贯要求）。

## 更根本的修法（未做，已登记在 docs/待修复问题清单.md）

让**服务端**在上传确认时按文件名补上 Content-Type（`MediaServiceImpl.confirm`
拿到 filename，完全可以顺手 `copy_object` 设一次）。
放在客户端就意味着"总会有下一个忘了带的调用方" —— 本脚本的存在本身就是证据：
`scripts/seed-demo-data.mjs`、M2/M6 的验收脚本当时都没带。
"""

import os
import sys
from collections import defaultdict
from pathlib import Path

from minio import Minio
from minio.commonconfig import REPLACE, CopySource

REPO_ROOT = Path(__file__).resolve().parent.parent
ENV_FILE = REPO_ROOT / "docker" / ".env"
BUCKET = "fitmind"

# 只处理"浏览器会按 MIME 决定怎么渲染"的类型 —— 其余（如 .txt/.json）不动
EXT_TO_TYPE = {
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
    ".gif": "image/gif",
    ".mp4": "video/mp4",
    ".webm": "video/webm",
}

# 判定为"需要修"的现状类型（MinIO 没拿到 Content-Type 时给的两个值）
WRONG_TYPES = {"binary/octet-stream", "application/octet-stream", "application/x-www-form-urlencoded", ""}


def load_env() -> dict:
    """从 docker/.env 读凭据（凭据不进 git，与其它脚本保持一致）"""
    env = {}
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def main() -> int:
    apply = "--apply" in sys.argv

    env = load_env()
    client = Minio(
        f"{env.get('MINIO_HOST', '127.0.0.1')}:{env.get('MINIO_API_PORT', '9000')}",
        access_key=env.get("MINIO_ROOT_USER", "fitmind"),
        secret_key=env.get("MINIO_ROOT_PASSWORD", "fitmind123"),
        secure=False,
    )

    print("=" * 72)
    print(" MinIO 对象 Content-Type " + ("修复（--apply）" if apply else "体检（只报告）"))
    print("=" * 72)

    to_fix = []          # [(key, 现类型, 目标类型)]
    skipped = defaultdict(int)
    total = 0

    for obj in client.list_objects(BUCKET, recursive=True):
        total += 1
        ext = os.path.splitext(obj.object_name)[1].lower()
        target = EXT_TO_TYPE.get(ext)
        if not target:
            skipped[f"扩展名 {ext or '（无）'} 不在处理范围"] += 1
            continue
        st = client.stat_object(BUCKET, obj.object_name)
        current = (st.content_type or "").strip()
        if current == target:
            skipped[f"已是 {target}"] += 1
            continue
        if current not in WRONG_TYPES:
            # 有明确的类型但与我们的推断不同 → 不擅自覆盖（可能是有人特意设的）
            skipped[f"类型为 {current}（与推断不同，不覆盖）"] += 1
            continue
        to_fix.append((obj.object_name, current or "(空)", target))

    print(f"\n  桶 {BUCKET} 共 {total} 个对象")
    print(f"  需要修复：{len(to_fix)} 个")
    for reason, n in skipped.items():
        print(f"  跳过：{n:>4} 个 —— {reason}")

    if not to_fix:
        print("\n  ✅ 没有需要修复的对象")
        return 0

    by_target = defaultdict(list)
    for key, cur, tgt in to_fix:
        by_target[tgt].append((key, cur))

    print("\n  将要修改：")
    for tgt, items in sorted(by_target.items()):
        print(f"\n    → {tgt}（{len(items)} 个）")
        for key, cur in items[:4]:
            print(f"        {key}   {cur} → {tgt}")
        if len(items) > 4:
            print(f"        … 其余 {len(items) - 4} 个同理")

    if not apply:
        print("\n  ⓘ 这是**阶段一**：没有修改任何东西。")
        print("    确认无误后执行：python scripts/fix-minio-content-type.py --apply")
        return 0

    print("\n  ---- 开始修复 ----")
    ok = 0
    fail = 0
    for key, _cur, tgt in to_fix:
        try:
            # ★ 就地改元数据：源与目标同一个 key，metadata_directive=REPLACE
            #   （只改 head，不重新上传内容）
            #
            # ⚠️ 第一版写成 `copy_object(..., content_type=tgt)` —— **这个方法根本没有
            #    `content_type` 参数**（签名只有 metadata/tags/retention/metadata_directive…），
            #    于是 507 个调用**全部抛 TypeError**。而我把异常只记成"失败计数"、
            #    打印的又只有前几行，`tail` 一看"没有报错"就以为改完了 ——
            #    真正兜住的是一句"改完必须自检"（复查显示 0/507 才暴露出问题）。
            #    Content-Type 属于对象元数据，必须走 `metadata` 这个口子。
            client.copy_object(
                BUCKET,
                key,
                CopySource(BUCKET, key),
                metadata={"Content-Type": tgt},
                metadata_directive=REPLACE,
            )
            ok += 1
        except Exception as e:  # noqa: BLE001 —— 单个失败不该中断整批
            fail += 1
            if fail <= 3:
                print(f"     ❌ {key}: {type(e).__name__}: {e}")

    print(f"\n  ✅ 已修复 {ok} 个" + (f"，**失败 {fail} 个**" if fail else ""))

    # 复查（★ 改完必须自检，不能只看"没报错"）
    print("\n  ---- 复查 ----")
    still = 0
    for key, _cur, tgt in to_fix:
        st = client.stat_object(BUCKET, key)
        if (st.content_type or "") != tgt:
            still += 1
            print(f"     ⚠️ {key} 仍是 {st.content_type}")
    print(f"  复查结果：{len(to_fix) - still}/{len(to_fix)} 已变为正确类型")
    return 0 if still == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
