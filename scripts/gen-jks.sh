#!/usr/bin/env bash
# ============================================================
# FitMind RSA 密钥库生成脚本
# ------------------------------------------------------------
# 用途：为 JWT(RS256) 生成非对称密钥对，供 fm-auth-service 签名使用。
#       fm-auth-service 通过 GET /jwks 对外暴露公钥，网关拉取公钥验签。
#
# 说明：*.jks 已加入 .gitignore，不入仓库。
#       他人克隆项目后执行本脚本即可重新生成一套密钥。
#
# 用法：bash scripts/gen-jks.sh
# ============================================================
set -e

KEYTOOL="D:/develop/jdk-17/bin/keytool.exe"
RES_DIR="D:/develop/develop/code/fitmind/fm-auth/fm-auth-service/src/main/resources"
JKS="$RES_DIR/fitmind.jks"

ALIAS="fitmind"
STOREPASS="fm123321"
KEYPASS="fm123321"
DNAME="CN=fitmind, OU=dev, O=fitmind, L=Nanjing, ST=Jiangsu, C=CN"
VALIDITY=3650

mkdir -p "$RES_DIR"

if [ -f "$JKS" ]; then
    echo "[跳过] 密钥库已存在：$JKS"
    echo "       如需重新生成，请先删除该文件。"
    exit 0
fi

echo "[生成] 正在创建 RSA 2048 密钥库..."
"$KEYTOOL" -genkeypair \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -sigalg SHA256withRSA \
    -keystore "$JKS" \
    -storetype JKS \
    -storepass "$STOREPASS" \
    -keypass "$KEYPASS" \
    -dname "$DNAME" \
    -validity "$VALIDITY"

echo ""
echo "[完成] 密钥库已生成：$JKS"
echo "[提示] 查看内容： keytool -list -keystore \"$JKS\" -storepass $STOREPASS"
