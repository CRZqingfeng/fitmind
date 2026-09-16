import os, re, urllib.request, urllib.error, datetime
from minio import Minio

# 从 .env 读凭据（不打印）
env = {}
for line in open("docker/.env", encoding="utf-8"):
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip().strip('"').strip("'")

AK, SK = env["MINIO_ROOT_USER"], env["MINIO_ROOT_PASSWORD"]
PORT = env.get("MINIO_API_PORT", "9000")
EP = f"localhost:{PORT}"
print(f"endpoint={EP}  ak_len={len(AK)} sk_len={len(SK)}")

c = Minio(EP, access_key=AK, secret_key=SK, secure=False)
b = "fitmind"
if not c.bucket_exists(b):
    c.make_bucket(b); print("bucket created:", b)
else:
    print("bucket exists:", b)

key = "probe/hello.txt"
data = b"fitmind-m2-1-probe"
put_url = c.presigned_put_object(b, key, expires=datetime.timedelta(hours=1))
print("[1] PUT 原 host ->", end=" ")
try:
    with urllib.request.urlopen(urllib.request.Request(put_url, data=data, method="PUT"), timeout=10) as r:
        print(r.status, "OK")
except urllib.error.HTTPError as e:
    print("HTTP", e.code, e.read()[:120].decode(errors="ignore"))

print("[2] PUT 换 127.0.0.1 ->", end=" ")
u2 = put_url.replace(f"localhost:{PORT}", f"127.0.0.1:{PORT}")
try:
    with urllib.request.urlopen(urllib.request.Request(u2, data=data, method="PUT"), timeout=10) as r:
        print(r.status, "OK")
except urllib.error.HTTPError as e:
    print("HTTP", e.code, e.read()[:120].decode(errors="ignore"))

print("[3] GET 预签名 ->", end=" ")
try:
    with urllib.request.urlopen(c.presigned_get_object(b, key, expires=datetime.timedelta(hours=1)), timeout=10) as r:
        print(r.status, r.read())
except urllib.error.HTTPError as e:
    print("HTTP", e.code, e.read()[:120].decode(errors="ignore"))

print("[4] 无签名直访 ->", end=" ")
try:
    with urllib.request.urlopen(f"http://localhost:{PORT}/{b}/{key}", timeout=10) as r:
        print(r.status)
except urllib.error.HTTPError as e:
    print("HTTP", e.code)
