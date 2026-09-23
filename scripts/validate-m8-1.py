"""M8-1 / A1 端到端验收：JWT TTL 修正 + **续期链路首次真正生效**

验收要点（不能只看"exp 数字变了"）：
  ① 新登录的 access 有效期 ≈ 30 分钟（原来是 30 天）
  ② refresh 有效期 ≈ 7 天（原来是 30 分钟）
  ③ Redis 里两个 jti key 的 TTL 与之一致
  ④ Cookie 的 Max-Age：记住我 → 7 天；不记住 → session
  ⑤ ★★ **续期链路**：无效 token → 401（前端触发续期的条件）
     → 带 HttpOnly Cookie 调 refresh → 换回可用 token
     （access 有 30 天时这条链路根本不会被走到，所以这是第一次验它）
  ⑥ ★ **顺带记录一个设计事实**：网关鉴权**不查 Redis jti**（只验签名 + 过期），
     所以"登出后旧 token 立刻失效"**不成立** —— 残留窗口等于 access 的有效期。
     这正是 A1 值得修的第二层理由（30 天 → 30 分钟）。

★ 修正记录（第一版验收写错过两处，留档避免重犯）：
   · 原先"删掉 jti 后期望 401"—— 错在把 **fm-auth 内部**的 jti 校验当成了**网关**的行为；
   · 原先 refresh 直连 8081 却带 `/as` 前缀 —— 错在把**网关前缀**当成了**服务路径**。
     服务本身的路径是 `/accounts/refresh`，`/as` 只在网关侧存在。
"""
import base64
import http.cookiejar
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

GW = "http://localhost:10010"
STUDENT = {"type": 1, "cellPhone": "13800010001", "password": "123456"}
REDIS_PASS = "123456"
REDIS_CONTAINER = None

passed = failed = 0
failures = []


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  ✅ {name}" + (f"（{detail}）" if detail else ""))
    else:
        failed += 1
        failures.append(f"{name} —— {detail}")
        print(f"  ❌ {name}" + (f"（{detail}）" if detail else ""))


def ok(name, detail=""):
    global passed
    passed += 1
    print(f"  ✅ {name}" + (f"（{detail}）" if detail else ""))


def section(t):
    print("\n" + "=" * 74)
    print(f"  {t}")
    print("=" * 74)


def jwt_payload(tok):
    p = tok.split(".")[1]
    p += "=" * (-len(p) % 4)
    return json.loads(base64.urlsafe_b64decode(p))


def redis(*args):
    r = subprocess.run(
        ["docker", "exec", REDIS_CONTAINER, "redis-cli", "-a", REDIS_PASS, "--no-auth-warning", *args],
        capture_output=True, text=True, timeout=20,
    )
    return (r.stdout or "").strip()


def find_redis():
    r = subprocess.run(["docker", "ps", "--format", "{{.Names}}\t{{.Image}}"],
                       capture_output=True, text=True, timeout=20)
    for line in (r.stdout or "").splitlines():
        if "redis" in line.lower():
            return line.split("\t")[0]
    return None


def login(remember_me=True):
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    req = urllib.request.Request(GW + "/as/accounts/login",
                                 data=json.dumps(dict(STUDENT, rememberMe=remember_me)).encode(),
                                 method="POST")
    req.add_header("Content-Type", "application/json")
    with opener.open(req, timeout=30) as r:
        data = json.loads(r.read().decode())
        set_cookie = r.headers.get("Set-Cookie", "")
    max_age = None
    if "Max-Age=" in set_cookie:
        max_age = int([x for x in set_cookie.split(";") if "Max-Age=" in x][0].split("=")[1].strip())
    return data.get("data"), max_age, jar, set_cookie


def http_call(method, path, token=None, jar=None):
    """统一 HTTP 调用（可带 CookieJar）；返回 (status, json)"""
    opener = (urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
              if jar else urllib.request.build_opener())
    req = urllib.request.Request(GW + path, method=method)
    if token:
        req.add_header("Authorization", token)
    try:
        with opener.open(req, timeout=30) as r:
            body = r.read().decode()
            return r.status, (json.loads(body) if body.strip() else {})
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}
    except Exception as e:
        return -1, {"error": str(e)}


REDIS_CONTAINER = find_redis()
print(f"  Redis 容器：{REDIS_CONTAINER}")
check("找到 Redis 容器", bool(REDIS_CONTAINER), str(REDIS_CONTAINER))
if not REDIS_CONTAINER:
    sys.exit(1)

# ============================================================
section("1. 新登录的 token 有效期（A1 的核心）")
# ============================================================
token, max_age, jar, _ = login(remember_me=True)
check("登录成功", bool(token), f"token 长度 {len(token) if token else 0}")

pl = jwt_payload(token)
now = int(time.time())
access_secs = pl["exp"] - now
print(f"     access exp 距今 {access_secs} 秒 = {access_secs / 60:.1f} 分钟")
check("★★ access 有效期 ≈ 30 分钟（原来 30 天）",
      1500 <= access_secs <= 1900, f"{access_secs} 秒 = {access_secs / 60:.1f} 分钟")

# ============================================================
section("2. Redis 里两个 jti key 的 TTL")
# ============================================================
acc_ttl = redis("TTL", "jwt:uid:1")
ref_ttl = redis("TTL", "jwt:refresh:uid:1")
print(f"     jwt:uid:1          TTL = {acc_ttl} 秒")
print(f"     jwt:refresh:uid:1  TTL = {ref_ttl} 秒")
check("★ access 的 jti TTL ≈ 1800 秒", acc_ttl.isdigit() and 1500 <= int(acc_ttl) <= 1900, f"{acc_ttl} 秒")
check("★★ refresh 的 jti TTL ≈ 7 天（604800 秒）",
      ref_ttl.isdigit() and 600000 <= int(ref_ttl) <= 610000, f"{ref_ttl} 秒")

# ============================================================
section("3. Cookie 的 Max-Age（记住我 vs 不记住）")
# ============================================================
print(f"     记住我 → Max-Age = {max_age}")
check("★ 记住我时 Cookie Max-Age = 7 天 = 604800", max_age == 604800, f"{max_age}")
_, max_age_no, _, _ = login(remember_me=False)
print(f"     不记住 → Max-Age = {max_age_no}（-1/None = session cookie）")
check("不记住我时 Cookie 是 session 级", max_age_no in (-1, None), f"{max_age_no}")

# ============================================================
section("4. ★★ 续期链路（access 30 天时从不被走到，这是第一次验）")
# ============================================================
token, _, jar, _ = login(remember_me=True)

s0, _ = http_call("GET", "/us/users/me", token=token)
check("续期前：带 access 请求受保护接口 → 200", s0 == 200, f"HTTP {s0}")

print("\n  ---- 4.1 ★ 记录一个设计事实（不是失败） ----")
old_jti = redis("GET", "jwt:uid:1")
print(f"     删掉 jwt:uid:1（原值 {old_jti}），模拟「登出 / 令牌已被吊销」")
redis("DEL", "jwt:uid:1")
time.sleep(0.5)
s_jti, r_jti = http_call("GET", "/us/users/me", token=token)
print(f"     同一个 token 经**网关**请求 → HTTP {s_jti} code={r_jti.get('code')}")
check("（如实断言现状）删 jti 后**网关仍放行** —— 网关鉴权只验签名+过期，不查 Redis jti",
      s_jti == 200, f"HTTP {s_jti}")
ok("★ 由此确认 A1 的第二层价值：「登出后旧 token 仍可用」的残留窗口"
   "从 **30 天** 缩到 **30 分钟**")

print("\n  ---- 4.2 续期链路本身 ----")
bad = "eyJhbGciOiJSUzI1NiJ9.eyJ1c2VyIjp7InVzZXJJZCI6MX19.broken-signature"
s_bad, _ = http_call("GET", "/us/users/me", token=bad)
check("★★ 无效 token → 401（这正是前端触发续期的条件）", s_bad in (401, 403), f"HTTP {s_bad}")

s_ref, r_ref = http_call("GET", "/as/accounts/refresh", jar=jar)
new_token = r_ref.get("data")
check("★★ 带 HttpOnly Cookie 调 refresh（走网关，真实链路）→ 换回新 token",
      s_ref == 200 and bool(new_token), f"HTTP {s_ref} code={r_ref.get('code')}")

if new_token:
    np = jwt_payload(new_token)
    print(f"     新 token 的 access 有效期 {(np['exp'] - int(time.time())) / 60:.1f} 分钟")
    s_new, r_new = http_call("GET", "/us/users/me", token=new_token)
    check("★★ 用新 token 请求受保护接口 → 200（续期链路完整可用）",
          s_new == 200, f"HTTP {s_new} code={r_new.get('code')}")
else:
    check("★★ 用新 token 请求受保护接口 → 200（续期链路完整可用）", False, "refresh 没返回 token")

# ============================================================
section("5. 收尾：恢复登录态")
# ============================================================
token2, _, _, _ = login(remember_me=True)
s2, _ = http_call("GET", "/us/users/me", token=token2)
check("重新登录后一切正常（环境已恢复）", s2 == 200, f"HTTP {s2}")
print(f"     jwt:uid:1 已重新写入（TTL={redis('TTL', 'jwt:uid:1')} 秒）")
print(f"     jwt:refresh:uid:1 TTL={redis('TTL', 'jwt:refresh:uid:1')} 秒")

print("\n" + "=" * 74)
print(f"  A1 验收结果：{passed} 通过 / {failed} 失败")
if failures:
    print("  失败项：")
    for f in failures:
        print(f"    · {f}")
print("=" * 74)
sys.exit(1 if failed else 0)
