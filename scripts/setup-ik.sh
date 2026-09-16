#!/usr/bin/env bash
# ============================================================
# 安装 / 修复 ES 中文分词插件 IK Analyzer（幂等，可重复执行）
# ------------------------------------------------------------
# 为什么要脚本而不是纯 docker-compose：
#   docker-compose 不支持安装插件，只能在容器内执行 elasticsearch-plugin install。
#   词典本身走 bind mount（docker/ik/），插件本体与官方词库走命名卷，本脚本负责把三者对齐。
#
# 为什么从 get.infini.cloud 装：
#   IK 已从 GitHub 迁移，medcl/elasticsearch-analysis-ik 的所有老下载地址现在全是 404。
#   新版发布在 infinilabs，地址是 get.infini.cloud。
#   ⚠️ 这个域名宿主机可能连不上，但 ES 容器内能连上（容器网络比宿主机宽松），
#      所以下载动作必须在容器内执行，不能在宿主机 curl 好再拷进去。
#
# 这个脚本会处理的三种状态：
#   ① 插件没装            → 安装
#   ② 插件在、官方词库丢了 → 重装（容器被删过，plugins 卷还在但 config 里的词库没了）
#                          症状很隐蔽：_analyze 报 NPE「_StopWords is null」而不是"找不到插件"
#   ③ 都正常              → 跳过
#
# 用法：bash scripts/setup-ik.sh
# ============================================================
set -uo pipefail

CONTAINER="${ES_CONTAINER:-fm-es}"
IK_VERSION="${IK_VERSION:-7.17.22}"
IK_URL="https://get.infini.cloud/elasticsearch/analysis-ik/${IK_VERSION}"
ES_PORT="${ES_PORT:-9201}"
IK_CFG_DIR="/usr/share/elasticsearch/config/analysis-ik"

echo "===== 1. 检查 ES 容器状态 ====="
if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    echo "❌ 容器 $CONTAINER 未运行。请先执行：cd docker && docker-compose -p fitmind up -d"
    exit 1
fi
echo "  ✅ $CONTAINER 运行中"

echo ""
echo "===== 2. 检查插件与官方词库 ====="
HAS_PLUGIN=0
HAS_DICT=0
docker exec "$CONTAINER" bin/elasticsearch-plugin list 2>/dev/null | grep -qx "analysis-ik" && HAS_PLUGIN=1
docker exec "$CONTAINER" test -f "$IK_CFG_DIR/main.dic" 2>/dev/null && HAS_DICT=1
echo "  插件: $([ $HAS_PLUGIN = 1 ] && echo 已安装 || echo 未安装)   官方词库: $([ $HAS_DICT = 1 ] && echo 存在 || echo 缺失)"

NEED_RESTART=0

if [ "$HAS_PLUGIN" = 1 ] && [ "$HAS_DICT" = 0 ]; then
    echo "  ⚠️ 插件在但官方词库丢了（容器被删过）。先移除插件目录再重装以恢复词库。"
    docker exec "$CONTAINER" rm -rf /usr/share/elasticsearch/plugins/analysis-ik
    HAS_PLUGIN=0
fi

if [ "$HAS_PLUGIN" = 0 ]; then
    echo ""
    echo "  开始下载安装（$IK_URL）..."
    if ! docker exec "$CONTAINER" bin/elasticsearch-plugin install --batch "$IK_URL"; then
        echo "❌ 安装失败。若报证书/网络错误，确认容器内能访问 get.infini.cloud。"
        exit 1
    fi
    NEED_RESTART=1
else
    echo "  ✅ 插件与词库都在，无需重装"
fi

echo ""
echo "===== 3. 同步健身自定义词典 ====="
# 词典文件已经 bind mount 到容器里，这里只做校验（防止挂载没生效）
for f in IKAnalyzer.cfg.xml fitmind.dic; do
    if docker exec "$CONTAINER" test -f "$IK_CFG_DIR/$f" 2>/dev/null; then
        echo "  ✅ $f 已在容器内"
    else
        echo "  ⚠️ $f 未挂载成功，请检查 docker-compose.yml 的 volumes 配置"
    fi
done
WORDS=$(docker exec "$CONTAINER" sh -c "wc -l < $IK_CFG_DIR/fitmind.dic" 2>/dev/null | tr -d ' \r')
echo "  自定义词典词条数：${WORDS:-未知}"

if [ "$NEED_RESTART" = 0 ]; then
    echo ""
    echo "  ℹ️ 插件未变更。若刚改过词典，需要让词典重新加载：docker restart $CONTAINER"
    if [ "${FORCE_RESTART:-0}" = "1" ]; then
        docker restart "$CONTAINER" > /dev/null && echo "  已按 FORCE_RESTART=1 重启"
    fi
else
    echo ""
    echo "===== 4. 重启 ES 让插件 / 词典生效 ====="
    docker restart "$CONTAINER" > /dev/null
    echo "  已重启，等待就绪..."
fi

for i in $(seq 1 40); do
    code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "http://localhost:${ES_PORT}/_cluster/health" 2>/dev/null || echo 000)
    if [ "$code" = "200" ]; then
        echo "  ✅ ES 就绪"
        break
    fi
    sleep 3
done

echo ""
echo "===== 5. 验证健身词典是否生效 ====="
for word in 增肌 减脂 硬拉 深蹲 哑铃卧推; do
    tokens=$(curl -s --max-time 8 -X POST "http://localhost:${ES_PORT}/_analyze" \
        -H "Content-Type: application/json" \
        -d "{\"analyzer\":\"ik_max_word\",\"text\":\"$word\"}" \
        | grep -o '"token":"[^"]*"' | sed 's/"token":"//;s/"//' | paste -sd, -)
    printf "  %-10s -> %s\n" "$word" "${tokens:-（无结果，可能 ES 还没就绪）}"
done
echo ""
echo "正常情况下每个词都会完整输出（没被拆成单字）。"
echo "词典文件：docker/ik/fitmind.dic　配置：docker/ik/IKAnalyzer.cfg.xml"
