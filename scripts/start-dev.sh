#!/usr/bin/env bash
# ============================================================
# FitMind 本地开发服务启动脚本
# ------------------------------------------------------------
# 用法：
#   bash scripts/start-dev.sh          # 启动所有已实现的服务
#   bash scripts/start-dev.sh fm-user  # 只启动指定服务
#
# 日志输出到 fitmind/logs/<服务名>.log
# ============================================================
set -e

JAVA="D:/develop/jdk-17/bin/java"
ROOT="D:/develop/develop/code/fitmind"
cd "$ROOT"
mkdir -p logs

# 目前已有可执行 jar 的服务（随里程碑增加）
# 注意：fm-auth 是聚合模块，真正的 jar 在其子模块 fm-auth-service 下，要写完整相对路径
ALL_SERVICES="fm-auth/fm-auth-service fm-user fm-media fm-gateway"

# MinIO 凭据从 docker/.env 读入，作为环境变量注入给需要对象存储的服务。
# 目的：yml 里只写 ${MINIO_ACCESS_KEY} 占位符，凭据不进 git。
MINIO_ENV=()
if [ -f "docker/.env" ]; then
    while IFS='=' read -r k v; do
        case "$k" in
            MINIO_ROOT_USER)     MINIO_ENV+=("MINIO_ACCESS_KEY=$v") ;;
            MINIO_ROOT_PASSWORD) MINIO_ENV+=("MINIO_SECRET_KEY=$v") ;;
        esac
    done < <(grep -E '^MINIO_ROOT_(USER|PASSWORD)=' docker/.env)
fi
if [ ${#MINIO_ENV[@]} -eq 0 ]; then
    echo "[警告] 未从 docker/.env 读到 MinIO 凭据，fm-media 将因缺少 access-key 启动失败"
fi

start_one() {
    local module="$1"
    local name
    name=$(basename "$module")
    local jar
    jar=$(ls "$module"/target/"$name"-1.0.0.jar 2>/dev/null | head -1)
    if [ -z "$jar" ]; then
        echo "[跳过] $module —— 未找到 jar，请先执行：bash scripts/mvn.sh clean install -DskipTests"
        return
    fi
    env "${MINIO_ENV[@]}" nohup "$JAVA" -jar "$jar" > "logs/$name.log" 2>&1 &
    echo "[启动] $name  PID=$!  日志=logs/$name.log"
}

TARGETS="$ALL_SERVICES"
if [ -n "$1" ]; then
    TARGETS="$1"
fi

for s in $TARGETS; do
    start_one "$s"
    sleep 2
done

echo ""
echo "服务启动中，约 20~30 秒后可用。"
echo "健康检查： curl http://localhost:10010/us/health"
echo "Nacos 控制台： http://localhost:8848/nacos  (nacos / nacos)"
