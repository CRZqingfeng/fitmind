#!/usr/bin/env bash
# ============================================================
# FitMind 按「端口」停服务（Windows / Git Bash）
# ------------------------------------------------------------
# 为什么需要这个脚本（本项目真实踩过的坑）：
#
#   1. 改完 yml / 代码要重启服务时，**必须先停掉旧进程**。
#      start-all.sh 自己也检测到这一点，会警告：
#        "请先停掉它再启动，否则你会拿到一个'看似新启动、实际是旧 jar'的服务"
#      —— 本项目就因此在旧代码上反复验证了好几轮。
#
#   2. ★★★ 2026-09-24 最终结论：**不要用 taskkill 杀进程，用 PowerShell 的 Stop-Process**
#
#      【踩过的三个坑，按时间顺序】
#      (a) 原始注释写着「这个 shell **不做** MSYS 路径转换，用单斜杠 `taskkill /F` 即可」
#          —— **错的**。MINGW64 Git Bash（含 WorkBuddy 内置的 PortableGit）**默认会转换**，
#          `/F` 被改写成 `F:/` → 报「错误: 无效参数/选项 - 'F:/'」→ **每次都说停失败**。
#          报错还被 `>/dev/null` 吞掉，只剩退出码非 0，于是端口一直不退、毫无线索。
#      (b) 于是加 `MSYS_NO_PATHCONV=1` 局部关掉转换 —— **部分场景有效，但不稳定**：
#          同一条命令行：shell 里直接跑**成功**（`成功: 已终止 PID 为 12580 的进程。`），
#          放进本脚本执行却**失败**（同机器 / 同 shell / 同一 PID / 只差几分钟）。
#          ⇒ "哪个环境变量能关掉转换"这件事**不可依赖**，别再赌。
#      (c) 结论：**换成没有任何斜杠参数的手段**。
#
#      【现在的做法】
#          powershell -NoProfile -Command "Stop-Process -Id <pid> -Force"
#        · 一个斜杠参数都没有 → 路径转换无从下手 → 与用哪种 shell 无关
#        · 成败只取决于"当前用户有没有权限杀这个进程"
#        · 失败时会把**真实报错**打出来（不再丢进 /dev/null）
#
#      ⚠️ 同理不要用 `tasklist /FI "..."`：参数会被转换，而它的**过滤条件会被静默忽略**，
#         于是 grep 找不到 PID，你会误判成「进程已退出」。
#         ★★ 所以判断进程死活一律以**端口**为准（`/dev/tcp/127.0.0.1/<port>`），不看进程名。
#
#   3. `netstat -ano` 里同一个端口有多行：LISTENING（有 PID）+ 多条 TIME_WAIT（PID 为 0）。
#      必须只取 LISTENING 那行，否则会去杀 PID 0。
#
# 用法：
#   bash scripts/stop-service.sh fm-media                 # 按服务名
#   bash scripts/stop-service.sh fm-media fm-promotion    # 多个
#   bash scripts/stop-service.sh --port 8083,8090         # 按端口
#   bash scripts/stop-service.sh --all                    # 停所有后端 + 两个前端
# ============================================================
set -u

# 服务名 -> 端口（与 start-all.sh 保持一致）
svc_port() {
    case "$1" in
        fm-auth|fm-auth-service) echo 8081 ;;
        fm-user)      echo 8082 ;;
        fm-media)     echo 8083 ;;
        fm-course)    echo 8084 ;;
        fm-search)    echo 8085 ;;
        fm-learning)  echo 8086 ;;
        fm-remark)    echo 8087 ;;
        fm-trade)     echo 8088 ;;
        fm-pay)       echo 8089 ;;
        fm-promotion) echo 8090 ;;
        fm-message)   echo 8091 ;;
        fm-exam)      echo 8092 ;;
        fm-data)      echo 8093 ;;
        fm-aigc)      echo 8094 ;;
        fm-gateway)   echo 10010 ;;
        portal|fitmind-portal) echo 5173 ;;
        admin|fitmind-admin)   echo 5174 ;;
        *)            echo "" ;;
    esac
}

ALL_SERVICES="fm-auth fm-user fm-media fm-course fm-search fm-learning fm-remark \
fm-trade fm-pay fm-promotion fm-message fm-exam fm-data fm-aigc fm-gateway portal admin"

# ---------- 解析参数 ----------
PAIRS=""   # 收集 "名称:端口"
if [ $# -eq 0 ]; then
    echo "用法：bash scripts/stop-service.sh <服务名...> | --port 8083,8090 | --all"
    exit 1
fi

if [ "$1" = "--all" ]; then
    for s in $ALL_SERVICES; do PAIRS="$PAIRS $s:$(svc_port "$s")"; done
elif [ "$1" = "--port" ]; then
    [ $# -ge 2 ] || { echo "❌ --port 后面要跟端口，如 --port 8083,8090"; exit 1; }
    for p in $(echo "$2" | tr ',' ' '); do PAIRS="$PAIRS port$p:$p"; done
else
    for s in "$@"; do
        p=$(svc_port "$s")
        if [ -z "$p" ]; then
            echo "  ⚠️ 不认识的服务名：$s（跳过）"
        else
            PAIRS="$PAIRS $s:$p"
        fi
    done
fi

# ---------- 逐个停 ----------
stopped=0
for pair in $PAIRS; do
    name="${pair%%:*}"
    port="${pair##*:}"

    # ★ 只取 LISTENING 那一行的 PID（TIME_WAIT 行的 PID 是 0，不能杀）
    pids=$(netstat -ano 2>/dev/null \
           | grep -E "[:.]${port}[[:space:]]+.*LISTENING" \
           | awk '{print $NF}' | grep -v '^0$' | sort -u)

    if [ -z "$pids" ]; then
        echo "  ⏭  $name (port=$port) 未在监听"
        continue
    fi

    for pid in $pids; do
        # ★★ 为什么用 PowerShell 的 Stop-Process，而不是 taskkill（2026-09-24 实测结论）★★
        #
        #   ① `taskkill /F /PID N` 里的 `/F` 会被 MINGW64 的 MSYS 路径转换改写成 `F:/`，
        #      报「错误: 无效参数/选项 - 'F:/'」→ **每次都停不掉**。
        #   ② 加 `MSYS_NO_PATHCONV=1` 在**部分场景**有效，但**实测不稳定**：
        #      同一条命令行，在 shell 里直接跑**成功**（`成功: 已终止 PID 为 12580 的进程。`），
        #      放进本脚本执行却**失败**（同机器、同 shell、同一 PID，前后只差几分钟）。
        #      → 说明"哪个环境变量能关掉转换"这件事**不可依赖**。
        #   ③ Stop-Process **没有任何斜杠参数**，路径转换无从下手 ——
        #      成败只取决于当前用户有没有权限杀这个进程，与用哪种 shell 无关。
        #
        # ★ 判成功一律用**退出码**，不要去 grep 输出文字：
        #   输出的语言与编码都随系统变（中文 GBK / 英文 UTF-8），grep 必然误报。
        # ★ 失败时**必须把原因打出来**：这里以前把报错丢进 /dev/null，
        #   结果用户只看到「停失败」而没有任何线索 —— 本次排查就为此多绕了一圈。
        if err="$(powershell -NoProfile -Command "Stop-Process -Id $pid -Force" 2>&1)"; then
            echo "  ✅ 已停 $name (port=$port pid=$pid)"
            stopped=$((stopped + 1))
        else
            echo "  ❌ 停 $name 失败 (pid=$pid)"
            printf '     %s\n' "$err" | grep -v '^[[:space:]]*$' | head -3 | sed 's/^/     /'
            echo "     ↳ 常见原因：进程属于别的用户 / 权限更高 / 已经退出"
        fi
    done
done

# ---------- 等端口真正释放 ----------
echo ""
echo "等待端口释放……"
for i in $(seq 1 10); do
    busy=0
    for pair in $PAIRS; do
        port="${pair##*:}"
        ((echo > /dev/tcp/127.0.0.1/$port) >/dev/null 2>&1) && busy=$((busy + 1))
    done
    if [ "$busy" -eq 0 ]; then
        echo "  ✅ 全部释放（共停 $stopped 个进程，第 $i 次探测）"
        exit 0
    fi
    sleep 1
done
echo "  ⚠️ 仍有 $busy 个端口在监听 —— 可能不是本脚本能停的进程，请手工检查"
exit 1
