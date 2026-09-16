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

export JAVA_HOME="D:\\develop\\jdk-17"
MVN_CMD="D:/develop/Maven/apache-maven-3.9.4-bin/apache-maven-3.9.4/bin/mvn.cmd"

exec "$MVN_CMD" "$@"
