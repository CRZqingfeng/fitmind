#!/usr/bin/env bash
# ============================================================
# FitMind Maven 包装脚本
# ------------------------------------------------------------
# 用途：解决 Git Bash 下直接调用 `mvn`（shell 脚本）会报
#       "找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher"
#       的问题。必须改用 Windows 批处理 mvn.cmd，并显式指定 JAVA_HOME。
#
# 用法：bash scripts/mvn.sh clean install -DskipTests
# ============================================================
set -e

# 优先使用环境里已设的 JAVA_HOME / MVN_HOME；未设置时才回落到本机默认路径。
# 换机器时：设好这两个环境变量即可，或直接改下面两行的默认值。
export JAVA_HOME="${JAVA_HOME:-D:\\develop\\jdk-17}"
MVN_HOME="${MVN_HOME:-D:/develop/Maven/apache-maven-3.9.4-bin/apache-maven-3.9.4}"
MVN_CMD="$MVN_HOME/bin/mvn.cmd"

exec "$MVN_CMD" "$@"
