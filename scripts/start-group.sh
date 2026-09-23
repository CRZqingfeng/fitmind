#!/usr/bin/env bash
# ============================================================
# 批量**串行**启动多个服务（自动化工具专用）
# ------------------------------------------------------------
# 用法：
#   bash scripts/start-group.sh fm-auth fm-user fm-gateway fm-course ...
#
# 为什么需要它（本项目真踩过两次）：
#   ① `start-all.sh` 的全量模式用 `&` 同时拉起 15 个 JVM —— 从自动化工具里跑，
#      脚本等 150 秒就退出，**父 shell 一退子进程全被回收**（脚本自己也警告了这点）。
#   ② 用工具的后台任务逐个跑单服务模式，又必须**并行**提交（一次 tool call 一个），
#      于是几个 JVM 同时启动 → 偶发 `Port xxx was already in use`
#      （实测 netstat 查不到占用者，属于启动瞬间的竞争）。
#      ★ 症状极具迷惑性：脚本报"启动失败"，但服务其实**起来了**（另一个进程的实例），
#        让人以为环境坏了。
#
# 这个脚本把两件事都解决：
#   · **串行**：拉起一个 → 等它的端口真的 LISTENING → 再拉下一个（没有竞争）
#   · **不退出**：最后 `wait` 持住，父 shell 一直在，子进程不会被回收
#     （调用方把它放进工具的后台任务里，任务的生命周期 = 这些服务的生命周期）
#
# ⚠️ 停服务：杀掉本任务（或按端口杀，见项目记忆「按端口杀进程」）。
# ============================================================
set -uo pipefail
export PATH="/usr/bin:/bin:$PATH"
cd "$(dirname "$0")/.." || exit 1
mkdir -p logs

unset SERVER__PORT SERVER__HOST SERVER_PORT SPRING_APPLICATION_JSON 2>/dev/null || true

env_get() {
    [ -f docker/.env ] || return 0
    grep -E "^$1=" docker/.env | head -1 | cut -d'=' -f2-
}
export MINIO_ACCESS_KEY="$(env_get MINIO_ROOT_USER)"
export MINIO_SECRET_KEY="$(env_get MINIO_ROOT_PASSWORD)"
export RABBITMQ_USER="$(env_get RABBITMQ_USER)"
export RABBITMQ_PASSWORD="$(env_get RABBITMQ_PASSWORD)"
export XXL_JOB_ADMIN="http://127.0.0.1:9090/xxl-job-admin"
export DEEPSEEK_API_KEY="$(env_get DEEPSEEK_API_KEY)"
export DASHSCOPE_API_KEY="$(env_get DASHSCOPE_API_KEY)"
XXL_TOKEN="$(env_get XXL_JOB_TOKEN)"
export XXL_JOB_TOKEN="${XXL_TOKEN:-default_token}"

# AI 模型通道：AI_MODE=mock|deepseek（默认 mock —— 验收要确定性）
AI_MODE="${AI_MODE:-mock}"
if [ "$AI_MODE" = "mock" ]; then
    export AIGC_CHAT_PROVIDER="mock"
    export AIGC_CHAT_ENABLED="false"
else
    export AIGC_CHAT_PROVIDER="deepseek"
    export AIGC_CHAT_ENABLED="true"
fi

JAVA="/d/develop/jdk-17/bin/java"

resolve() {
    case "$1" in
        fm-auth-service|fm-auth) echo "8081 fm-auth/fm-auth-service/target/fm-auth-service-1.0.0.jar" ;;
        fm-user)      echo "8082 fm-user/target/fm-user-1.0.0.jar" ;;
        fm-media)     echo "8083 fm-media/target/fm-media-1.0.0.jar" ;;
        fm-course)    echo "8084 fm-course/target/fm-course-1.0.0.jar" ;;
        fm-search)    echo "8085 fm-search/target/fm-search-1.0.0.jar" ;;
        fm-learning)  echo "8086 fm-learning/target/fm-learning-1.0.0.jar" ;;
        fm-remark)    echo "8087 fm-remark/target/fm-remark-1.0.0.jar" ;;
        fm-trade)     echo "8088 fm-trade/target/fm-trade-1.0.0.jar" ;;
        fm-pay)       echo "8089 fm-pay/target/fm-pay-1.0.0.jar" ;;
        fm-promotion) echo "8090 fm-promotion/target/fm-promotion-1.0.0.jar" ;;
        fm-message)   echo "8091 fm-message/target/fm-message-1.0.0.jar" ;;
        fm-exam)      echo "8092 fm-exam/target/fm-exam-1.0.0.jar" ;;
        fm-data)      echo "8093 fm-data/target/fm-data-1.0.0.jar" ;;
        fm-aigc)      echo "8094 fm-aigc/target/fm-aigc-1.0.0.jar" ;;
        fm-gateway)   echo "10010 fm-gateway/target/fm-gateway-1.0.0.jar" ;;
        *) return 1 ;;
    esac
}

port_open() { (echo > "/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1; }

if [ $# -eq 0 ]; then
    echo "用法：bash scripts/start-group.sh <服务名> [服务名...]"
    exit 1
fi

echo "===== 串行启动 $# 个服务（AI_MODE=$AI_MODE）====="
for name in "$@"; do
    info="$(resolve "$name")" || { echo "  ❌ 未知服务：$name"; continue; }
    port="${info%% *}"
    jar="${info#* }"

    if [ ! -f "$jar" ]; then
        echo "  ❌ $name 缺少 jar：$jar（先跑 bash scripts/mvn.sh install -DskipTests）"
        continue
    fi

    if port_open "$port"; then
        echo "  ⏭ $name (端口 $port) 已在运行，跳过"
        continue
    fi

    echo "  ▶ 启动 $name (端口 $port) …"
    "$JAVA" -jar "$jar" --server.port="$port" > "logs/$name.log" 2>&1 &

    # ★ 关键：等这个端口真的 LISTENING 再去拉下一个（消除启动竞争）
    for i in $(seq 1 40); do
        if port_open "$port"; then break; fi
        sleep 1
    done
    if port_open "$port"; then
        echo "     ✅ $name 就绪（${i}s）"
    else
        echo "     ⚠️ $name 端口 $port 40 秒内未监听（看 logs/$name.log）"
    fi
done

echo ""
echo "===== 启动完成，进入保持模式（杀掉本任务即停服务） ====="
wait
