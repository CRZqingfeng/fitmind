#!/usr/bin/env bash
# ============================================================
# FitMind 本地服务启动
# ------------------------------------------------------------
# 两种用法：
#
#   1) 单服务前台模式（自动化工具/脚本调用用这个）：
#        bash scripts/start-all.sh fm-learning
#      直接 exec 到前台，由调用方（终端或工具的后台任务）持有进程生命周期。
#
#   2) 全量模式（人在交互终端里手动跑）：
#        bash scripts/start-all.sh
#      内部用 & 后台启动 7 个服务 —— ⚠️ 只在真实交互终端里有效，
#      从自动化工具里跑会因为父 shell 退出而回收子进程。
#
# ⚠️ 必须显式指定 --server.port：
#    宿主环境可能导出 SERVER__PORT（Spring Boot 松散绑定会当成 server.port），
#    不指定就会去抢宿主端口，报 "Web server failed to start. Port 4207 was already in use"。
#
# ⚠️ 不能直接 source docker/.env：里面有 ES_JAVA_OPTS=-Xmx512m -Xms512m 带空格，
#    shell 会把后半截当命令执行。故逐行取值。
# ============================================================
set -uo pipefail
export PATH="/usr/bin:/bin:$PATH"

cd "$(dirname "$0")/.." || exit 1
mkdir -p logs

# 防宿主环境污染
unset SERVER__PORT SERVER__HOST SERVER_PORT SPRING_APPLICATION_JSON 2>/dev/null || true

env_get() {
    [ -f docker/.env ] || return 0
    grep -E "^$1=" docker/.env | head -1 | cut -d'=' -f2-
}
export MINIO_ACCESS_KEY="$(env_get MINIO_ROOT_USER)"
export MINIO_SECRET_KEY="$(env_get MINIO_ROOT_PASSWORD)"

# ==================== M9-1 对象存储可插拔（minio / oss 二选一） ====================
# ⚠️ 这一段在 start-group.sh 里有**一份同样的**（两个脚本各自独立注入环境变量）。
#    改这里务必同步改那里，否则"用 A 脚本起能连上 OSS、用 B 脚本起连不上"，
#    表现是 403 / NoSuchBucket，极难往"两个脚本不一致"上想。
#
# ⚠️ 只在取值非空时才 export：环境变量一旦存在（哪怕空串）就会覆盖 yml 的默认值，
#    把 bucket 变成空字符串 → 上传跑到根目录，报错完全不像配置问题。
export_if_set() {
    v="$(env_get "$2")"
    [ -n "$v" ] && export "$1=$v"
    return 0
}
export FM_STORAGE_TYPE="${FM_STORAGE_TYPE:-minio}"
if [ "$FM_STORAGE_TYPE" = "oss" ]; then
    export_if_set FM_STORAGE_BUCKET OSS_BUCKET
    export_if_set OSS_ENDPOINT OSS_ENDPOINT
    export_if_set OSS_PUBLIC_ENDPOINT OSS_PUBLIC_ENDPOINT
    export_if_set OSS_REGION OSS_REGION
    export_if_set OSS_ACCESS_KEY_ID OSS_ACCESS_KEY_ID
    export_if_set OSS_ACCESS_KEY_SECRET OSS_ACCESS_KEY_SECRET
fi

export RABBITMQ_USER="$(env_get RABBITMQ_USER)"
export RABBITMQ_PASSWORD="$(env_get RABBITMQ_PASSWORD)"
export XXL_JOB_ADMIN="http://127.0.0.1:9090/xxl-job-admin"
# ★ 大模型密钥（M6 fm-aigc）：放在 docker/.env 里（已被 gitignore），
#   这里逐行取值注入 —— 绝不写进 application.yml
export DEEPSEEK_API_KEY="$(env_get DEEPSEEK_API_KEY)"
export DASHSCOPE_API_KEY="$(env_get DASHSCOPE_API_KEY)"
XXL_TOKEN="$(env_get XXL_JOB_TOKEN)"
export XXL_JOB_TOKEN="${XXL_TOKEN:-default_token}"

# ==================== AI 模型通道（M6 fm-aigc） ====================
# 用法（$2 或环境变量，优先级从左到右）：
#   bash scripts/start-all.sh fm-aigc mock            强制 Mock（验收脚本用）
#   bash scripts/start-all.sh fm-aigc deepseek        强制真实模型
#   bash scripts/start-all.sh fm-aigc                 有 key 走 DeepSeek，没 key 自动降级 Mock
#
# ★ 为什么把选择逻辑放在这里，而不是让它散在 yml 里：
#   provider 与 spring.ai.openai.chat.enabled 必须**同时**切换
#   （provider=mock 时不关掉 OpenAI 自动配置，会同时存在两个 ChatModel Bean）。
#   "两个开关要一致"是个很容易忘的约定 —— 封装成一处，就不会忘。
AIGC_REQ="${2:-${AIGC_CHAT_PROVIDER:-}}"
if [ "$AIGC_REQ" = "mock" ]; then
    export AIGC_CHAT_PROVIDER="mock"
    export AIGC_CHAT_ENABLED="false"
    echo "★ fm-aigc 使用 Mock 模型通道（指定 mock）"
elif [ "$AIGC_REQ" = "deepseek" ]; then
    export AIGC_CHAT_PROVIDER="deepseek"
    export AIGC_CHAT_ENABLED="true"
elif [ -z "${DEEPSEEK_API_KEY:-}" ]; then
    export AIGC_CHAT_PROVIDER="mock"
    export AIGC_CHAT_ENABLED="false"
    echo "⚠️ 未配置 DEEPSEEK_API_KEY → fm-aigc 自动改用 Mock 模型通道（功能可跑，只是回答是确定性的）"
else
    export AIGC_CHAT_PROVIDER="deepseek"
    export AIGC_CHAT_ENABLED="true"
fi

JAVA="/d/develop/jdk-17/bin/java"

# 服务名 -> "端口 jar路径"
resolve() {
    case "$1" in
        fm-auth-service|fm-auth) echo "8081 fm-auth/fm-auth-service/target/fm-auth-service-1.0.0.jar" ;;
        fm-user)     echo "8082 fm-user/target/fm-user-1.0.0.jar" ;;
        fm-media)    echo "8083 fm-media/target/fm-media-1.0.0.jar" ;;
        fm-course)   echo "8084 fm-course/target/fm-course-1.0.0.jar" ;;
        fm-search)   echo "8085 fm-search/target/fm-search-1.0.0.jar" ;;
        fm-learning) echo "8086 fm-learning/target/fm-learning-1.0.0.jar" ;;
        fm-remark)   echo "8087 fm-remark/target/fm-remark-1.0.0.jar" ;;
        fm-trade)    echo "8088 fm-trade/target/fm-trade-1.0.0.jar" ;;
        fm-pay)      echo "8089 fm-pay/target/fm-pay-1.0.0.jar" ;;
        fm-promotion) echo "8090 fm-promotion/target/fm-promotion-1.0.0.jar" ;;
        fm-message)  echo "8091 fm-message/target/fm-message-1.0.0.jar" ;;
        fm-exam)     echo "8092 fm-exam/target/fm-exam-1.0.0.jar" ;;
        # ⚠️ fm-aigc 是唯一用独立 parent（Boot 3.5.16）的模块，见其 pom 注释
        fm-aigc)     echo "8094 fm-aigc/target/fm-aigc-1.0.0.jar" ;;
        fm-data)     echo "8093 fm-data/target/fm-data-1.0.0.jar" ;;
        fm-gateway)  echo "10010 fm-gateway/target/fm-gateway-1.0.0.jar" ;;
        *) return 1 ;;
    esac
}

# 端口是否已监听
port_open() { (echo > "/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1; }

# ---------- 单服务前台模式 ----------
if [ $# -ge 1 ]; then
    info="$(resolve "$1")" || { echo "未知服务：$1"; exit 1; }
    port="${info%% *}"
    jar="${info#* }"
    if [ ! -f "$jar" ]; then
        echo "❌ 缺少 jar：$jar（先跑 bash scripts/mvn.sh install -DskipTests）"
        exit 1
    fi
    # ⚠️ 启动前先看端口有没有被占 —— 这是本环境最容易骗过自己的一个坑：
    #   如果旧进程还活着，新进程会因端口冲突启动失败，而日志又被本行
    #   下方的 `>` **覆盖**掉，于是你看到"日志文件时间没变"，却以为服务是新的，
    #   实际跑的一直是**旧 jar**（本项目就因此在旧代码上反复验证了好几轮）。
    #   Windows 上按进程名/路径匹配杀进程还容易失手（jar 是相对路径），
    #   所以这里把"端口已占用"直接当成错误暴露出来，而不是默默启动失败。
    if port_open "$port"; then
        echo "❌ 端口 $port 已被占用 —— 服务很可能已在运行（旧进程未停干净）。"
        echo "   请先停掉它再启动，否则你会拿到一个'看似新启动、实际是旧 jar'的服务："
        echo "     powershell -NoProfile -Command \"Get-CimInstance Win32_Process -Filter \\\"Name='java.exe'\\\" | Where-Object { \$_.CommandLine -like '*-jar*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }\""
        exit 1
    fi
    echo "▶ 启动 $1（端口 $port，日志 logs/$1.log）"
    exec "$JAVA" -jar "$jar" --server.port="$port" > "logs/$1.log" 2>&1
fi

# ---------- 全量模式 ----------
start() {
    local name="$1" port="$2" jar="$3"
    if [ ! -f "$jar" ]; then
        echo "  ❌ $name 缺少 jar：$jar"
        return
    fi
    if port_open "$port"; then
        echo "  ⏭ $name 已在 $port 运行，跳过"
        return
    fi
    "$JAVA" -jar "$jar" --server.port="$port" > "logs/$name.log" 2>&1 &
    echo "  ▶ $name (端口 $port)"
}

echo "===== 启动 FitMind 微服务（全量模式）====="
start fm-auth-service 8081 fm-auth/fm-auth-service/target/fm-auth-service-1.0.0.jar
start fm-user         8082 fm-user/target/fm-user-1.0.0.jar
start fm-media        8083 fm-media/target/fm-media-1.0.0.jar
start fm-course       8084 fm-course/target/fm-course-1.0.0.jar
start fm-search       8085 fm-search/target/fm-search-1.0.0.jar
start fm-learning     8086 fm-learning/target/fm-learning-1.0.0.jar
start fm-remark       8087 fm-remark/target/fm-remark-1.0.0.jar
start fm-trade        8088 fm-trade/target/fm-trade-1.0.0.jar
start fm-pay          8089 fm-pay/target/fm-pay-1.0.0.jar
start fm-promotion    8090 fm-promotion/target/fm-promotion-1.0.0.jar
start fm-message      8091 fm-message/target/fm-message-1.0.0.jar
start fm-exam         8092 fm-exam/target/fm-exam-1.0.0.jar
start fm-data         8093 fm-data/target/fm-data-1.0.0.jar
start fm-aigc         8094 fm-aigc/target/fm-aigc-1.0.0.jar
start fm-gateway      10010 fm-gateway/target/fm-gateway-1.0.0.jar

echo ""
echo "===== 等待端口就绪（最多 150 秒） ====="
for i in $(seq 1 50); do
    sleep 3
    up=0
    for port in 8081 8082 8083 8084 8085 8086 8087 8088 8089 8090 8091 8092 8093 8094 10010; do
        port_open "$port" && up=$((up + 1))
    done
    [ "$up" -eq 11 ] && { echo "✅ 端口全部就绪"; break; }
    printf "\r  已就绪 %s/11 ..." "$up"
done
echo ""
echo "⚠️ 本环境从自动化工具里跑全量模式时，脚本一退出子进程就会被回收；"
echo "   请改用单服务模式逐个启动：bash scripts/start-all.sh <服务名>"
