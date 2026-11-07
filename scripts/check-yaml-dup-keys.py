#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
YAML 重复键检查（Spring Boot 启动前必跑）。

为什么需要它
------------
SnakeYAML 对**同一个 mapping 里的重复 key 会直接抛 DuplicateKeyException**，
而 Spring Boot 的表现是"应用启动失败"，堆栈里只有一行
`while constructing a mapping ... found duplicate key listener`——
不告诉你是哪个文件、也不在编译期报错。

也就是说：**YAML 结构错误能在 `mvn package` 全绿的情况下让服务起不来**。
2026-11-07 就踩过一次：批量给 11 个服务的 application.yml 插 MQ 配置时，
fm-data / fm-search 里本来就有 `spring.rabbitmq.listener`，
新插入的 `listener:` 造成重复键，两个服务启动直接失败，
而当时的验证只起了 fm-user / fm-pay / fm-gateway 三个服务，漏掉了它们。

用法
----
    python scripts/check-yaml-dup-keys.py            # 检查全部 application*.yml
    python scripts/check-yaml-dup-keys.py fm-data    # 只检查某个模块

退出码：0 = 无重复键；1 = 发现问题（可用于 CI 前置检查）。

已知不检查的情况
----------------
- YAML 列表（`- xxx`）内部的键重复 —— 那是合法结构（如网关的 routes 每项都有 uri/predicates）
- `---` 分隔的多文档：各文档独立计算，同名 key 出现在不同 profile 文档里是合法的
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 只匹配 "key:" 形式；跳过列表项、注释、空行
KEY_RE = re.compile(r"^(\s*)([A-Za-z0-9_.\-]+):(\s|$)")


def scan(path):
    """返回 [(行号, 父级路径, key, 首次出现行号)]"""
    problems = []
    doc = 0
    stack = []          # [(indent, key)] 当前嵌套路径
    seen = {}           # (doc, parent, key) -> 首次行号
    item_seq = 0        # 列表元素计数器：每个 `- ` 开一个新的作用域

    for lineno, raw in enumerate(io.open(path, "r", encoding="utf-8").read().split("\n"), 1):
        line = raw.rstrip()

        if line.strip() == "---":
            doc += 1
            stack = []
            continue
        if not line.strip() or line.lstrip().startswith("#"):
            continue

        # 列表项（`- xxx`）：它开启一个**新的 mapping 作用域**，
        # 后续同级的 key（uri/predicates…）属于这个元素，不是"重复键"。
        # 这里用一个不重复的伪 key 占位，让每个元素的父路径互不相同。
        stripped = line.lstrip()
        if stripped.startswith("-"):
            indent = len(line) - len(stripped)
            while stack and stack[-1][0] >= indent:
                stack.pop()
            item_seq += 1
            stack.append((indent, "#item%d" % item_seq))
            # 处理 `- name: xxx` 这种"列表项直接带 key"的写法
            rest = stripped[1:].strip()
            m_inline = KEY_RE.match(" " * (indent + 2) + rest)
            if m_inline and not rest.startswith("#"):
                stack.append((indent + 2, m_inline.group(2)))
            continue

        m = KEY_RE.match(line)
        if not m:
            continue

        indent = len(m.group(1))
        key = m.group(2)
        while stack and stack[-1][0] >= indent:
            stack.pop()
        parent = "/".join(k for _, k in stack)
        sig = (doc, parent, key)
        if sig in seen:
            problems.append((lineno, parent or "<root>", key, seen[sig]))
        else:
            seen[sig] = lineno
        stack.append((indent, key))

    return problems


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    targets = []
    # 递归找 **/src/main/resources/application*.yml —— 注意 fm-auth 这类多级模块
    # （fm-auth/fm-auth-service/src/main/resources），只扫一层会漏掉它。
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames
                       if d not in ("target", "node_modules", ".git", ".backup")]
        norm = dirpath.replace("\\", "/")
        if not norm.endswith("src/main/resources"):
            continue
        if only:
            rel = os.path.relpath(dirpath, ROOT).replace("\\", "/")
            if not rel.startswith(only):
                continue
        for f in sorted(filenames):
            if f.startswith("application") and f.endswith((".yml", ".yaml")):
                targets.append(os.path.join(dirpath, f))
    targets.sort()

    if not targets:
        print("没找到任何 application*.yml（路径对吗？）")
        return 1

    total = 0
    for path in targets:
        probs = scan(path)
        if not probs:
            continue
        total += len(probs)
        rel = os.path.relpath(path, ROOT).replace("\\", "/")
        print("\n[重复键] %s" % rel)
        for lineno, parent, key, first in probs:
            print("  第 %4d 行 key='%s' 父级=%s（首次出现在第 %d 行）"
                  % (lineno, key, parent, first))

    if total:
        print("\n共 %d 处重复键。SnakeYAML 会抛 DuplicateKeyException → 服务启动失败。" % total)
        print("修法：把第二个同名 key 的内容合并进第一个，而不是并列写两个。")
        return 1

    print("OK：%d 份配置文件无重复键" % len(targets))
    return 0


if __name__ == "__main__":
    sys.exit(main())
